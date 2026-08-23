package com.example.mapasssist

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentSecondBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TextFileViewModel by viewModels()

    private var currentTextFile: TextFile? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val textFileId = arguments?.getInt("textFileId") ?: -1
        if (textFileId != -1) {
            lifecycleScope.launch {
                currentTextFile = viewModel.getTextFileById(textFileId)
                currentTextFile?.let {
                    binding.editTitle.setText(it.title)
                    binding.editContent.setText(it.content)
                }
            }
        }

        binding.buttonShare.setOnClickListener {
            saveAndShare()
        }

        requireActivity().addMenuProvider(object : androidx.core.view.MenuProvider {
            override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
                menuInflater.inflate(R.menu.menu_second, menu)
            }

            override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_save -> {
                        saveFile()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner)
    }

    private fun saveFile() {
        val title = binding.editTitle.text.toString()
        val content = binding.editContent.text.toString()

        if (title.isEmpty()) {
            Toast.makeText(requireContext(), "Title cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val textFile = currentTextFile?.copy(
            title = title,
            content = content,
            lastModified = System.currentTimeMillis()
        ) ?: TextFile(title = title, content = content)

        if (textFile.id == 0) {
            viewModel.insert(textFile)
        } else {
            viewModel.update(textFile)
        }
        Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
    }

    private fun saveAndShare() {
        val title = binding.editTitle.text.toString()
        val content = binding.editContent.text.toString()

        binding.exportTitle.text = title
        binding.exportContent.text = content

        val view = binding.exportView
        view.post {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            shareBitmap(bitmap)
        }
    }

    private fun shareBitmap(bitmap: Bitmap) {
        val cachePath = File(requireContext().cacheDir, "images")
        cachePath.mkdirs()
        val file = File(cachePath, "shared_text.png")
        val stream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.close()

        val contentUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)

        if (contentUri != null) {
            val shareIntent = Intent()
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            shareIntent.setDataAndType(contentUri, requireContext().contentResolver.getType(contentUri))
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
            shareIntent.type = "image/png"
            startActivity(Intent.createChooser(shareIntent, "Share with"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
