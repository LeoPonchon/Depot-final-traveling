package com.shimtraveling.data.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.UserProfileChangeRequest
import com.shimtraveling.data.firestore.FirestoreRepository
import com.shimtraveling.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.tasks.await
import java.util.*

class AuthException(
    val emailError: String? = null,
    val passwordError: String? = null,
    val usernameError: String? = null,
    val generalError: String? = null
) : Exception(generalError ?: emailError ?: passwordError ?: usernameError ?: "Erreur d'authentification")

class UserRepository(
    private val firestore: FirestoreRepository,
    private val auth: FirebaseAuth
) {

    private var currentUser: User? = null
    private val _userFlow = MutableSharedFlow<Result<User?>>(replay = 1)

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    fun getCurrentUser(): Flow<Result<User?>> = _userFlow.onSubscription {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            currentUser = null
            _userFlow.tryEmit(Result.success(null))
        } else if (firebaseUser.isAnonymous) {
            currentUser = null
            _userFlow.tryEmit(Result.success(null))
        } else if (currentUser == null) {
            loadUserProfile(firebaseUser.uid)
        } else {
            _userFlow.tryEmit(Result.success(currentUser))
        }
    }

    private suspend fun loadUserProfile(uid: String) {
        val result = firestore.getUserById(uid)
        result.onSuccess { user ->
            val firebaseUser = auth.currentUser
            val effectiveUser = if (firebaseUser?.isAnonymous == true || firebaseUser == null) {
                null
            } else {
                val isIncomplete = user == null || user.username.isBlank() || user.email.isBlank()
                if (isIncomplete) {
                    val email = firebaseUser.email.orEmpty()
                    val username = firebaseUser.displayName
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: email.substringBefore("@").ifBlank { "Voyageur" }
                    val repaired = User(
                        id = firebaseUser.uid,
                        username = username,
                        email = email.ifBlank { "${firebaseUser.uid}@local" },
                        createdAt = Date()
                    )
                    firestore.addUser(repaired)
                    repaired
                } else {
                    user
                }
            }
            currentUser = effectiveUser
            _userFlow.tryEmit(Result.success(effectiveUser))
        }
        result.onFailure { error ->
            currentUser = null
            _userFlow.tryEmit(Result.failure(error))
        }
    }

    fun login(email: String, password: String): Flow<Result<User>> = flow {
        try {
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user
            if (firebaseUser != null) {
                val userResult = firestore.getUserById(firebaseUser.uid)
                if (userResult.isFailure) {
                    emit(Result.failure(userResult.exceptionOrNull() ?: Exception("Impossible de charger le profil.")))
                    return@flow
                }
                val user = userResult.getOrNull()
                val isIncomplete = user == null || user.username.isBlank() || user.email.isBlank()
                if (!isIncomplete) {
                    currentUser = user
                    _userFlow.emit(Result.success(user))
                    emit(Result.success(user))
                } else {
                    val effectiveEmail = (firebaseUser.email ?: email).ifBlank { email }
                    val newUser = User(
                        id = firebaseUser.uid,
                        username = firebaseUser.displayName ?: effectiveEmail.substringBefore("@"),
                        email = effectiveEmail,
                        createdAt = Date()
                    )
                    val write = firestore.addUser(newUser)
                    if (write.isFailure) {
                        emit(Result.failure(write.exceptionOrNull() ?: Exception("Impossible d'enregistrer le profil.")))
                        return@flow
                    }
                    currentUser = newUser
                    _userFlow.emit(Result.success(newUser))
                    emit(Result.success(newUser))
                }
            } else {
                emit(Result.failure(AuthException(generalError = "Erreur de connexion.")))
            }
        } catch (e: Exception) {
            emit(Result.failure(mapLoginException(e)))
        }
    }

    fun register(username: String, email: String, password: String): Flow<Result<User>> = flow {
        try {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user

            if (firebaseUser != null) {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(username)
                    .build()
                firebaseUser.updateProfile(profileUpdates).await()

                val newUser = User(
                    id = firebaseUser.uid,
                    username = username,
                    email = email,
                    createdAt = Date()
                )

                val write = firestore.addUser(newUser)
                if (write.isFailure) {
                    emit(Result.failure(write.exceptionOrNull() ?: Exception("Impossible d'enregistrer le profil.")))
                    return@flow
                }
                currentUser = newUser
                _userFlow.emit(Result.success(newUser))
                emit(Result.success(newUser))
            } else {
                emit(Result.failure(Exception("Erreur lors de la création du compte")))
            }
        } catch (e: Exception) {
            val message = when {
                e.message?.contains("email") == true -> "Cet email est déjà utilisé"
                e.message?.contains("password") == true -> "Le mot de passe doit contenir au moins 6 caractères"
                else -> "Erreur lors de l'inscription: ${e.message}"
            }
            emit(Result.failure(Exception(message)))
        }
    }

    private fun mapLoginException(error: Exception): AuthException {
        return when (error) {
            is FirebaseAuthInvalidUserException -> AuthException(
                emailError = "Aucun compte n'est associé à cet email."
            )
            is FirebaseAuthInvalidCredentialsException -> when (error.errorCode) {
                "ERROR_INVALID_EMAIL" -> AuthException(
                    emailError = "L'adresse email n'est pas valide."
                )
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> AuthException(
                    passwordError = "Mot de passe incorrect."
                )
                else -> AuthException(
                    generalError = "Email ou mot de passe incorrect."
                )
            }
            is FirebaseTooManyRequestsException -> AuthException(
                generalError = "Trop de tentatives. Réessayez dans quelques instants."
            )
            is FirebaseNetworkException -> AuthException(
                generalError = "Connexion réseau indisponible. Vérifiez Internet."
            )
            is FirebaseAuthException -> AuthException(
                generalError = "Connexion impossible : ${error.localizedMessage ?: error.errorCode}."
            )
            else -> AuthException(
                generalError = "Connexion impossible pour le moment."
            )
        }
    }

    fun logout(): Flow<Result<Unit>> = flow {
        auth.signOut()
        currentUser = null
        _userFlow.emit(Result.success(null))
        emit(Result.success(Unit))
    }

    fun updateProfile(user: User): Flow<Result<User>> = flow {
        try {
            val updateResult = firestore.updateUser(user)
            if (updateResult.isSuccess) {
                currentUser = user
                _userFlow.emit(Result.success(user))
                emit(Result.success(user))
            } else {
                emit(Result.failure(updateResult.exceptionOrNull() ?: Exception("Update failed")))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }

    private suspend fun updateFavoriteList(
        field: String,
        itemId: String,
        add: Boolean
    ): Result<Unit> {
        val user = currentUser ?: return Result.failure(Exception("Non connecté"))
        val current = when (field) {
            "photoFavorites" -> user.photoFavorites
            "placeFavorites" -> user.placeFavorites
            "pathFavorites" -> user.pathFavorites
            else -> user.favorites
        }
        val updated = if (add) (current + itemId).distinct() else current - itemId
        val updatedUser = when (field) {
            "photoFavorites" -> user.copy(photoFavorites = updated)
            "placeFavorites" -> user.copy(placeFavorites = updated)
            "pathFavorites" -> user.copy(pathFavorites = updated)
            else -> user.copy(favorites = updated)
        }
        return firestore.updateUser(updatedUser).also { result ->
            if (result.isSuccess) {
                currentUser = updatedUser
                _userFlow.emit(Result.success(updatedUser))
            }
        }
    }

    suspend fun addFavoriteSuspend(itemId: String) =
        updateFavoriteList("favorites", itemId, true)

    suspend fun removeFavoriteSuspend(itemId: String) =
        updateFavoriteList("favorites", itemId, false)

    suspend fun addPhotoFavoriteSuspend(id: String) =
        updateFavoriteList("photoFavorites", id, true)

    suspend fun removePhotoFavoriteSuspend(id: String) =
        updateFavoriteList("photoFavorites", id, false)

    suspend fun addPlaceFavoriteSuspend(id: String) =
        updateFavoriteList("placeFavorites", id, true)

    suspend fun removePlaceFavoriteSuspend(id: String) =
        updateFavoriteList("placeFavorites", id, false)

    suspend fun addPathFavoriteSuspend(id: String) =
        updateFavoriteList("pathFavorites", id, true)

    suspend fun removePathFavoriteSuspend(id: String) =
        updateFavoriteList("pathFavorites", id, false)

    suspend fun getNotifications(): Result<List<com.shimtraveling.data.model.AppNotification>> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in"))
        return firestore.getNotifications(userId).map { docs ->
            docs.map { doc ->
                com.shimtraveling.data.model.AppNotification(
                    id = doc.id,
                    title = doc.title,
                    message = doc.message,
                    type = com.shimtraveling.data.model.NotificationType.valueOf(doc.type),
                    createdAt = java.util.Date(doc.createdAt),
                    isRead = doc.isRead,
                    relatedId = doc.relatedId
                )
            }
        }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = firestore.markNotificationAsRead(notificationId)

    suspend fun refreshCurrentUser() {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null || firebaseUser.isAnonymous) {
            currentUser = null
            _userFlow.emit(Result.success(null))
        } else {
            loadUserProfile(firebaseUser.uid)
        }
    }

    suspend fun updateAvatar(avatarUrl: String): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
            val updateResult = firestore.updateUserAvatar(userId, avatarUrl)
            if (updateResult.isSuccess) {
                currentUser = currentUser?.copy(avatar = avatarUrl)
                currentUser?.let { _userFlow.emit(Result.success(it)) }
            }
            updateResult
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveNotificationSettings(settings: com.shimtraveling.data.model.NotificationSettings): Result<Unit> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        return firestore.saveNotificationSettings(userId, settings)
    }

    suspend fun getNotificationSettings(): Result<com.shimtraveling.data.model.NotificationSettings> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        return firestore.getNotificationSettings(userId)
    }
}
