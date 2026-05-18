
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {getMessaging} = require("firebase-admin/messaging");
const {setGlobalOptions} = require("firebase-functions/v2");
const {onDocumentCreated} = require("firebase-functions/v2/firestore");
const {onCall, HttpsError} = require("firebase-functions/v2/https");

setGlobalOptions({region: "europe-west9"});

initializeApp();
const db = getFirestore();
const messaging = getMessaging();

async function assertCallerIsAdmin(request) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  const uid = request.auth.uid;
  const snap = await db.collection("users").doc(uid).get();
  const isAdmin = snap.exists && snap.data() && snap.data().isAdmin === true;
  if (!isAdmin) {
    throw new HttpsError("permission-denied", "Admin access required.");
  }
  return uid;
}

async function purgeFirestoreExceptAdmin(adminUid) {
  const stats = {
    collectionsPurged: [],
    usersPurged: 0,
  };

  const cols = await db.listCollections();
  for (const col of cols) {
    if (col.id === "users") continue;
    await db.recursiveDelete(col);
    stats.collectionsPurged.push(col.id);
  }

  const usersSnap = await db.collection("users").get();
  for (const doc of usersSnap.docs) {
    if (doc.id === adminUid) continue;
    await db.recursiveDelete(doc.ref);
    stats.usersPurged += 1;
  }

  return stats;
}

async function purgeAuthExceptAdmin(adminUid) {
  const auth = getAuth();
  let nextPageToken = undefined;
  let deleted = 0;

  do {
    const page = await auth.listUsers(1000, nextPageToken);
    nextPageToken = page.pageToken;
    const uids = page.users.map((u) => u.uid).filter((uid) => uid && uid !== adminUid);
    if (uids.length > 0) {
      const res = await auth.deleteUsers(uids);
      deleted += (res.successCount || 0);
    }
  } while (nextPageToken);

  return {deletedAuthUsers: deleted};
}

async function purgeStorageAll() {
  const bucket = getStorage().bucket();
  let pageToken = undefined;
  let deleted = 0;

  do {
    const [files, , resp] = await bucket.getFiles({maxResults: 1000, pageToken});
    pageToken = resp && resp.nextPageToken ? resp.nextPageToken : undefined;
    if (!files || files.length === 0) continue;

    for (const f of files) {
      await f.delete({ignoreNotFound: true});
      deleted += 1;
    }
  } while (pageToken);

  return {deletedStorageObjects: deleted};
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

async function resolveAuthorIds(tokens) {
  const out = new Set();
  for (const raw of tokens || []) {
    const t = String(raw || "").trim();
    if (!t) continue;
    const byId = await db.collection("users").doc(t).get();
    if (byId.exists) {
      out.add(t);
      continue;
    }
    let q = await db.collection("users").where("username", "==", t).limit(1).get();
    if (q.empty) {
      const titled = t.length > 0 ? t.charAt(0).toUpperCase() + t.slice(1) : t;
      q = await db.collection("users").where("username", "==", titled).limit(1).get();
    }
    if (!q.empty) out.add(q.docs[0].id);
  }
  return out;
}

async function loadNotificationSettings(uid) {
  const ref = db.collection("users").doc(uid).collection("settings").doc("notifications");
  const snap = await ref.get();
  if (!snap.exists) {
    return {
      newPhotoFromUser: true,
      newPhotoInGroup: true,
      newPhotoNearby: true,
      newPhotoWithTag: true,
      newPhotoInFollowedPlace: false,
      newPhotoInFollowedCategory: false,
      nearbyRadiusKm: 5,
      followedUsers: [],
      followedTags: [],
      followedPlaceIds: [],
      followedCategories: [],
    };
  }
  const d = snap.data();
  return {
    newPhotoFromUser: d.newPhotoFromUser !== false,
    newPhotoInGroup: d.newPhotoInGroup !== false,
    newPhotoNearby: d.newPhotoNearby !== false,
    newPhotoWithTag: d.newPhotoWithTag !== false,
    newPhotoInFollowedPlace: d.newPhotoInFollowedPlace === true,
    newPhotoInFollowedCategory: d.newPhotoInFollowedCategory === true,
    nearbyRadiusKm: typeof d.nearbyRadiusKm === "number" ? d.nearbyRadiusKm : 5,
    followedUsers: Array.isArray(d.followedUsers) ? d.followedUsers : [],
    followedTags: Array.isArray(d.followedTags) ? d.followedTags : [],
    followedPlaceIds: Array.isArray(d.followedPlaceIds) ? d.followedPlaceIds : [],
    followedCategories: Array.isArray(d.followedCategories) ? d.followedCategories : [],
  };
}

function tagsMatch(followedLower, photoTags) {
  for (const t of photoTags || []) {
    const tl = String(t).toLowerCase();
    for (const f of followedLower) {
      if (!f) continue;
      if (tl.includes(f) || f.includes(tl)) return true;
    }
  }
  return false;
}


exports.onPhotoCreatedNotifySubscribers = onDocumentCreated("photos/{photoId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const p = snap.data();
  const photoId = snap.id;
  const authorId = p.authorId || "";
  const visibility = p.visibility || "PUBLIC";
  const groupId = p.groupId || null;
  const moderation = p.moderationStatus || "VISIBLE";
  if (moderation === "HIDDEN") return;
  if (visibility === "PRIVATE") return;

  const placeId = p.placeId || "";
  const category = (p.category || "OTHER").toUpperCase();
  const photoTags = (p.tags || []).map((x) => String(x).toLowerCase());
  const plat = typeof p.latitude === "number" ? p.latitude : parseFloat(p.latitude);
  const plng = typeof p.longitude === "number" ? p.longitude : parseFloat(p.longitude);

  const usersSnap = await db.collection("users").get();
  const recipients = [];

  for (const doc of usersSnap.docs) {
    const uid = doc.id;
    if (!uid || uid === authorId) continue;
    const u = doc.data() || {};
    const token = u.fcmToken;
    if (!token || typeof token !== "string") continue;

    if (visibility === "GROUP") {
      const members = Array.isArray(u.groups) ? u.groups : [];
      if (!groupId || !members.includes(groupId)) continue;
    }

    const settings = await loadNotificationSettings(uid);
    const userGroups = Array.isArray(u.groups) ? u.groups : [];
    const followedUserTokens = [...new Set([
      ...settings.followedUsers,
      ...(Array.isArray(u.followedUsers) ? u.followedUsers : []),
    ])];
    const followedTags = [...new Set([
      ...settings.followedTags.map((t) => String(t).toLowerCase()),
      ...(Array.isArray(u.followedTags) ? u.followedTags : []).map((t) => String(t).toLowerCase()),
    ])].filter(Boolean);
    const followedPlaces = [...new Set([
      ...settings.followedPlaceIds.map((x) => String(x).trim()).filter(Boolean),
      ...(Array.isArray(u.followedPlaces) ? u.followedPlaces : []).map((x) => String(x).trim()),
    ])].filter(Boolean);
    const followedCategories = [...new Set(
      settings.followedCategories.map((c) => String(c).toUpperCase())
    )].filter(Boolean);

    const followedAuthorIds = await resolveAuthorIds(followedUserTokens);
    for (const t of followedUserTokens) {
      const s = String(t).trim();
      if (s.length >= 20 && /^[a-zA-Z0-9]+$/.test(s)) followedAuthorIds.add(s);
    }

    const hasConfiguredRules =
      (settings.newPhotoFromUser && followedUserTokens.length > 0) ||
      (settings.newPhotoInGroup && userGroups.length > 0) ||
      settings.newPhotoNearby ||
      (settings.newPhotoWithTag && followedTags.length > 0) ||
      (settings.newPhotoInFollowedPlace && followedPlaces.length > 0) ||
      (settings.newPhotoInFollowedCategory && followedCategories.length > 0);

    let match = false;
    if (!hasConfiguredRules) {
      match = true;
    } else {
      if (settings.newPhotoFromUser && followedAuthorIds.size > 0 && followedAuthorIds.has(authorId)) {
        match = true;
      }
      if (settings.newPhotoInGroup && groupId && userGroups.includes(groupId)) {
        match = true;
      }
      if (settings.newPhotoNearby &&
          typeof u.notifyLastLat === "number" && typeof u.notifyLastLng === "number" &&
          !Number.isNaN(plat) && !Number.isNaN(plng) && plat !== 0 && plng !== 0) {
        const km = haversineKm(u.notifyLastLat, u.notifyLastLng, plat, plng);
        if (km <= settings.nearbyRadiusKm) match = true;
      }
      if (settings.newPhotoWithTag && followedTags.length > 0 && tagsMatch(followedTags, photoTags)) {
        match = true;
      }
      if (settings.newPhotoInFollowedPlace && followedPlaces.length > 0 && placeId && followedPlaces.includes(placeId)) {
        match = true;
      }
      if (settings.newPhotoInFollowedCategory && followedCategories.length > 0 && followedCategories.includes(category)) {
        match = true;
      }
    }

    if (match) recipients.push({uid, token});
  }

  const title = `Nouvelle photo à ${p.placeName || "un lieu"}`;
  const body = `${p.authorName || "Un voyageur"} a partagé une nouvelle photo.`;

  const chunk = 500;
  for (let i = 0; i < recipients.length; i += chunk) {
    const slice = recipients.slice(i, i + chunk);
    const message = {
      tokens: slice.map((x) => x.token),
      data: {
        type: "NEW_PHOTO",
        title,
        body,
        photoId: String(photoId),
      },
      android: {priority: "high"},
    };
    try {
      await messaging.sendEachForMulticast(message);
    } catch (e) {
      console.error("FCM multicast error", e);
    }
  }
});


exports.onReportCreatedNotifyAdmins = onDocumentCreated("reports/{reportId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const r = snap.data();
  const status = r.status || "OPEN";
  if (status !== "OPEN" && status !== "") return;

  const admins = await db.collection("users").where("isAdmin", "==", true).get();
  const tokens = [];
  for (const d of admins.docs) {
    const t = d.data().fcmToken;
    if (t && typeof t === "string") tokens.push(t);
  }
  if (tokens.length === 0) return;

  const title = "Nouveau signalement";
  const body = "Un contenu a été signalé. Ouvrez la modération dans l’app.";

  const chunk = 500;
  for (let i = 0; i < tokens.length; i += chunk) {
    const batch = tokens.slice(i, i + chunk);
    try {
      await messaging.sendEachForMulticast({
        tokens: batch,
        data: {
          type: "NEW_REPORT",
          title,
          body,
          reportId: snap.id,
          photoId: String(r.photoId || ""),
        },
        android: {priority: "high"},
      });
    } catch (e) {
      console.error("FCM admin notify error", e);
    }
  }
});


exports.purgeProjectExceptAdmin = onCall(
  {
    timeoutSeconds: 540,
    memory: "1GiB",
  },
  async (request) => {
    const adminUid = await assertCallerIsAdmin(request);

    const firestoreStats = await purgeFirestoreExceptAdmin(adminUid);
    const storageStats = await purgeStorageAll();
    const authStats = await purgeAuthExceptAdmin(adminUid);

    return {
      ok: true,
      adminUid,
      ...firestoreStats,
      ...storageStats,
      ...authStats,
    };
  }
);
