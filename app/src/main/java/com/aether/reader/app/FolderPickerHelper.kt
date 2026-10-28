package com.aether.reader.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

object FolderPickerHelper {

    val BOOK_EXTENSIONS = setOf(
        "epub", "pdf", "txt", "cbz", "cbr", "mobi", "azw", "azw3", "md", "markdown", "text", "zip", "fb2"
    )
    val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

    fun show(
        context: Context,
        dialogTitle: String,
        initialDir: File = Environment.getExternalStorageDirectory(),
        allowedExtensions: Set<String>,
        onFileSelected: ((File) -> Unit)? = null,
        onFilesSelected: ((List<File>) -> Unit)? = null,
        onSystemPickerFallback: (() -> Unit)? = null
    ) {
        // Android 11+ (API 30+) MANAGE_EXTERNAL_STORAGE permission prompt if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:" + context.packageName)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "기기의 모든 파일 접근 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    } catch (_: Throwable) {}
                }
            }
        }

        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        var currentDir: File = if (initialDir.exists() && initialDir.canRead()) initialDir else Environment.getExternalStorageDirectory()

        val isMultiCapable = (onFilesSelected != null)
        var isMultiSelect = false
        val selectedFiles = LinkedHashSet<File>()
        var currentMatchingFiles: List<File> = emptyList()

        lateinit var dlg: AlertDialog
        var renderDir: (File) -> Unit = {}
        var updateMultiUi: () -> Unit = {}

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(ReaderTheme.EINK_PAPER)
        }

        // 1. Sticky Header Bar: [◀ 뒤로] + Title + [☑ 복수 선택] + [✕ 닫기]
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val backBtn = context.pillButton("◀ 뒤로", ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && parent.absolutePath != currentDir.absolutePath) {
                renderDir(parent)
            } else {
                dlg.dismiss()
            }
        }.apply {
            textSize = 12f
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }

        val titleTv = TextView(context).apply {
            text = dialogTitle
            textSize = 15f
            setTextColor(ReaderTheme.EINK_INK)
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(6), 0, dp(6), 0)
        }

        val multiToggleBtn = if (isMultiCapable) {
            context.pillButton("☑ 복수 선택", ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
                isMultiSelect = !isMultiSelect
                if (!isMultiSelect) selectedFiles.clear()
                updateMultiUi()
                renderDir(currentDir)
            }.apply {
                textSize = 11.5f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
        } else null

        val closeBtn = context.pillButton("✕", ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            dlg.dismiss()
        }.apply {
            textSize = 13f
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }

        headerRow.addView(backBtn)
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (multiToggleBtn != null) {
            headerRow.addView(multiToggleBtn)
            headerRow.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
        }
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        val pathTv = TextView(context).apply {
            text = "📂 " + currentDir.absolutePath
            textSize = 11.5f
            setTextColor(ReaderTheme.EINK_MUTED)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(pathTv)

        // Real-time Search Box
        var searchQuery = ""
        val searchInput = EditText(context).apply {
            hint = "🔍 파일명 실시간 검색..."
            setHintTextColor(ReaderTheme.EINK_MUTED)
            setTextColor(ReaderTheme.EINK_INK)
            textSize = 13f
            setBackground(cardDrawable(ReaderTheme.EINK_CARD_BG, 6, ReaderTheme.EINK_BORDER))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                true
            }
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Quick Jumps (Root, Books, Download, Documents, SDCard)
        val quickJumps = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        val storageRoot = Environment.getExternalStorageDirectory()
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val booksDir = File(storageRoot, "Books")

        val quickScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        quickScroll.addView(quickJumps)
        root.addView(quickScroll)

        // Scrollable list container
        val scroll = ScrollView(context).apply {
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 8, ReaderTheme.EINK_BORDER)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            enableVisibleScrollbar()
        }
        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        val maxH = (dm.heightPixels * 0.48f).toInt().coerceAtLeast(dp(200))
        root.addView(scroll, LinearLayout.LayoutParams(-1, maxH))

        // Multi-select Action Bar (when onFilesSelected is provided)
        val multiActionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = if (isMultiCapable) View.VISIBLE else View.GONE
        }

        val selectAllBtn = context.pillButton("전체 선택", ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            if (currentMatchingFiles.isNotEmpty() && selectedFiles.containsAll(currentMatchingFiles)) {
                selectedFiles.removeAll(currentMatchingFiles.toSet())
            } else {
                isMultiSelect = true
                selectedFiles.addAll(currentMatchingFiles)
            }
            updateMultiUi()
            renderDir(currentDir)
        }.apply {
            textSize = 11.5f
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }

        val addSelectedBtn = context.pillButton("선택한 도서 추가 (0권)", Color.WHITE, ReaderTheme.ACCENT_KINDLE) {
            if (selectedFiles.isEmpty()) {
                Toast.makeText(context, "선택된 도서가 없습니다. 목록에서 도서를 터치하여 선택해 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                val list = selectedFiles.toList()
                dlg.dismiss()
                onFilesSelected?.invoke(list)
            }
        }.apply {
            textSize = 12f
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }

        val addFolderAllBtn = context.pillButton("📂 폴더 전체 추가", ReaderTheme.ACCENT_KINDLE, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
            if (currentMatchingFiles.isEmpty()) {
                Toast.makeText(context, "현재 폴더에 추가할 수 있는 도서가 없습니다.", Toast.LENGTH_SHORT).show()
            } else {
                val list = currentMatchingFiles.toList()
                dlg.dismiss()
                onFilesSelected?.invoke(list)
            }
        }.apply {
            textSize = 11.5f
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }

        if (isMultiCapable) {
            multiActionBar.addView(selectAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            multiActionBar.addView(addSelectedBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            multiActionBar.addView(addFolderAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(multiActionBar)
        }

        updateMultiUi = {
            if (isMultiCapable) {
                if (isMultiSelect) {
                    multiToggleBtn?.text = "✓ 다중 선택 중"
                    multiToggleBtn?.background = cardDrawable(ReaderTheme.ACCENT_KINDLE, 6, ReaderTheme.ACCENT_KINDLE)
                    multiToggleBtn?.setTextColor(Color.WHITE)
                } else {
                    multiToggleBtn?.text = "☑ 복수 선택"
                    multiToggleBtn?.background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, ReaderTheme.EINK_BORDER)
                    multiToggleBtn?.setTextColor(ReaderTheme.EINK_INK)
                }
                addSelectedBtn.text = if (selectedFiles.isEmpty()) "선택 도서 추가 (0권)" else "선택한 ${selectedFiles.size}권 책장에 추가"
                addFolderAllBtn.text = "📂 폴더 전체 (${currentMatchingFiles.size}권) 추가"
            }
        }

        // Bottom Action Bar: [◀ 상위 폴더] + [✕ 취소] + [안드로이드 시스템 탐색기로 열기]
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val upFolderBtn = context.pillButton("◀ 상위 폴더", ReaderTheme.EINK_INK, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && parent.absolutePath != currentDir.absolutePath) {
                renderDir(parent)
            } else {
                Toast.makeText(context, "최상위 폴더입니다.", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bottomRow.addView(upFolderBtn)

        if (onSystemPickerFallback != null) {
            bottomRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val sysBtn = context.pillButton("안드로이드 시스템 탐색기로 열기", ReaderTheme.ACCENT_RIDI_BLUE, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
                dlg.dismiss()
                onSystemPickerFallback()
            }.apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            bottomRow.addView(sysBtn)
        }
        root.addView(bottomRow)

        fun performRender(dir: File) {
            currentDir = dir
            pathTv.text = "📂 " + dir.absolutePath
            listLayout.removeAllViews()

            // 1. Up Directory Row if parent exists and is readable
            val parent = dir.parentFile
            if (parent != null && parent.canRead()) {
                val upRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = context.pillDrawable(ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER)
                    setOnClickListener {
                        searchInput.text?.clear()
                        performRender(parent)
                    }
                }
                val upTv = TextView(context).apply {
                    text = "⬆️ [상위 폴더로 이동 (..)]"
                    textSize = 13.5f
                    setTextColor(ReaderTheme.ACCENT_KINDLE)
                    typeface = ReaderTheme.uiFontBold
                }
                upRow.addView(upTv)
                listLayout.addView(upRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                })
            }

            // 2. Query Subdirectories & Matching Files
            val rawFiles = try {
                dir.listFiles() ?: emptyArray()
            } catch (_: Throwable) {
                emptyArray()
            }

            val query = searchQuery.trim().lowercase(Locale.ROOT)
            val subDirs = rawFiles.filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
                .filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            val matchingFiles = rawFiles.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in allowedExtensions }
                .filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

            currentMatchingFiles = matchingFiles
            updateMultiUi()

            if (subDirs.isEmpty() && matchingFiles.isEmpty()) {
                val emptyTv = TextView(context).apply {
                    text = if (query.isNotEmpty()) "검색 결과가 없습니다." else "이 폴더에 열 수 있는 도서 파일이 없습니다."
                    textSize = 12f
                    setTextColor(ReaderTheme.EINK_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(24), dp(12), dp(24))
                }
                listLayout.addView(emptyTv)
                return
            }

            // Render subdirectories
            for (sub in subDirs) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    isClickable = true
                    background = context.pillDrawable(ReaderTheme.EINK_CARD_BG, Color.TRANSPARENT)
                    setOnClickListener {
                        searchInput.text?.clear()
                        performRender(sub)
                    }
                }
                val icon = TextView(context).apply {
                    text = "📁 "
                    textSize = 14f
                }
                val name = TextView(context).apply {
                    text = sub.name
                    textSize = 13.5f
                    setTextColor(ReaderTheme.EINK_INK)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                row.addView(icon)
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                listLayout.addView(row)
                listLayout.addView(thinDivider(context))
            }

            // Render matching files
            for (f in matchingFiles) {
                val isSelected = selectedFiles.contains(f)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    isClickable = true
                    background = if (isSelected) cardDrawable(0x28FF9900.toInt(), 6, ReaderTheme.ACCENT_KINDLE)
                        else context.pillDrawable(ReaderTheme.EINK_CARD_BG, Color.TRANSPARENT)
                    setOnClickListener {
                        if (isMultiSelect) {
                            if (selectedFiles.contains(f)) {
                                selectedFiles.remove(f)
                            } else {
                                selectedFiles.add(f)
                            }
                            updateMultiUi()
                            performRender(dir)
                        } else {
                            dlg.dismiss()
                            if (onFilesSelected != null) onFilesSelected(listOf(f)) else onFileSelected?.invoke(f)
                        }
                    }
                    if (isMultiCapable) {
                        setOnLongClickListener {
                            isMultiSelect = true
                            if (selectedFiles.contains(f)) selectedFiles.remove(f) else selectedFiles.add(f)
                            updateMultiUi()
                            performRender(dir)
                            Toast.makeText(context, "복수 선택 모드 (${f.name})", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                }
                val icon = TextView(context).apply {
                    text = when (f.extension.lowercase(Locale.ROOT)) {
                        "pdf" -> "📕 "
                        "epub" -> "📘 "
                        "cbz", "cbr" -> "🎨 "
                        "ttf", "otf", "woff", "woff2" -> "🔤 "
                        else -> "📄 "
                    }
                    textSize = 14f
                }
                val nameCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val nameTv = TextView(context).apply {
                    text = f.name
                    textSize = 13f
                    setTextColor(if (isSelected) ReaderTheme.ACCENT_KINDLE else ReaderTheme.EINK_INK)
                    typeface = ReaderTheme.uiFontBold
                }
                val sizeTv = TextView(context).apply {
                    val kb = f.length() / 1024
                    val sizeStr = if (kb >= 1024) String.format(Locale.ROOT, "%.1f MB", kb / 1024f) else "$kb KB"
                    text = "${f.extension.uppercase(Locale.ROOT)}  •  $sizeStr"
                    textSize = 10.5f
                    setTextColor(ReaderTheme.EINK_MUTED)
                }
                nameCol.addView(nameTv)
                nameCol.addView(sizeTv)

                row.addView(icon)
                row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val selectBadge = TextView(context).apply {
                    text = if (isSelected) "✓ 선택됨" else "선택"
                    textSize = 11f
                    setTextColor(ReaderTheme.ACCENT_KINDLE)
                    typeface = if (isSelected) ReaderTheme.uiFontBold else Typeface.DEFAULT
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                row.addView(selectBadge)

                listLayout.addView(row)
                listLayout.addView(thinDivider(context))
            }
        }

        renderDir = { performRender(it) }

        // Setup Quick Jump Buttons with listener
        fun setupQuickJumps() {
            quickJumps.removeAllViews()
            val list = mutableListOf(
                "📁 최상위" to storageRoot,
                "📥 다운로드" to downloadDir,
                "📑 문서" to docDir,
                "📚 Books" to booksDir
            )
            // External SDCard check
            val extDirs = context.getExternalFilesDirs(null)
            if (extDirs != null && extDirs.size > 1 && extDirs[1] != null) {
                val sdRoot = extDirs[1]?.parentFile?.parentFile?.parentFile?.parentFile
                if (sdRoot != null && sdRoot.exists()) {
                    list.add("💾 외장 SD카드" to sdRoot)
                }
            }

            for ((label, target) in list) {
                val btn = context.pillButton(label, ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
                    if (target.exists() && target.canRead()) {
                        searchInput.text?.clear()
                        performRender(target)
                    } else {
                        target.mkdirs()
                        if (target.exists() && target.canRead()) {
                            searchInput.text?.clear()
                            performRender(target)
                        } else {
                            Toast.makeText(context, "$label 폴더에 접근할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.apply {
                    textSize = 11.5f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                quickJumps.addView(btn)
                quickJumps.addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
            }
        }
        setupQuickJumps()

        // TextWatcher for real-time search
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                performRender(currentDir)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        performRender(currentDir)

        dlg = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(true)
            .create()

        dlg.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                val parent = currentDir.parentFile
                if (parent != null && parent.canRead() && parent.absolutePath != currentDir.absolutePath) {
                    searchInput.text?.clear()
                    performRender(parent)
                    true
                } else {
                    dlg.dismiss()
                    true
                }
            } else {
                false
            }
        }

        dlg.showWithOptimalWidth(context)
    }

    private fun thinDivider(context: Context): View {
        val dm = context.resources.displayMetrics
        val dp1 = (1 * dm.density).toInt().coerceAtLeast(1)
        return View(context).apply {
            setBackgroundColor(ReaderTheme.EINK_BORDER)
            layoutParams = LinearLayout.LayoutParams(-1, dp1).apply {
                setMargins((6 * dm.density).toInt(), 0, (6 * dm.density).toInt(), 0)
            }
        }
    }
}
