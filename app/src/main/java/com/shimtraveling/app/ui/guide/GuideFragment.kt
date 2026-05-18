package com.shimtraveling.ui.guide

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.shimtraveling.R
import com.shimtraveling.databinding.FragmentGuideBinding

class GuideFragment : Fragment() {

    private var _binding: FragmentGuideBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.openShareButton.setOnClickListener {
            findNavController().navigate(R.id.navigation_share)
        }

        binding.openPathButton.setOnClickListener {
            findNavController().navigate(R.id.navigation_path)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

