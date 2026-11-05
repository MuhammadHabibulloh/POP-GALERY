package id.go.bgn.popsipgn

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * MainActivity untuk POP SIPGN BGN
 * Alamat Web: https://pop-sipgn.bgn.go.id/
 *
 * Fitur Khusus:
 * 1. Menangani onShowFileChooser: Setiap permintaan kamera dialihkan langsung ke Galeri Foto HP.
 * 2. Injeksi JavaScript DOM: Menghapus atribut capture="camera" pada elemen input file agar web engine tidak memaksa kamera.
 * 3. Swipe to Refresh, Progress Bar, Cookie Persistence, dan Back navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: View

    // Callback untuk mengembalikan file hasil pilihan galeri ke WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Activity Result Launcher untuk Galeri HP
    private lateinit var galleryLauncher: ActivityResultLauncher<Intent>

    companion object {
        const val TARGET_URL = "https://pop-sipgn.bgn.go.id/"
        const val USER_AGENT_SUFFIX = " POPSIPGN-WebView/1.0"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupLaunchers()
        setupWebView()
        setupBackNavigation()

        // Muat halaman awal
        webView.loadUrl(TARGET_URL)
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        errorLayout = findViewById(R.id.errorLayout)

        findViewById<View>(R.id.btnRetry)?.setOnClickListener {
            errorLayout.visibility = View.GONE
            webView.reload()
        }

        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }
    }

    private fun setupLaunchers() {
        // Pemilih foto sistem: hanya memberikan akses ke foto yang dipilih pengguna.
        galleryLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = filePathCallback
            if (callback == null) return@registerForActivityResult

            var selectedUris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clipData = data?.clipData
                selectedUris = when {
                    clipData != null -> Array(clipData.itemCount) { index ->
                        clipData.getItemAt(index).uri
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
            callback.onReceiveValue(selectedUris)
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        // Aktifkan JavaScript & Local Storage
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Pengaturan Cache & Performa
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // User Agent
        settings.userAgentString = settings.userAgentString + USER_AGENT_SUFFIX

        // Cookie Manager
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Setup WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorLayout.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false

                // INJEKSI JAVASCRIPT: Mengubah semua tombol kamera di web agar langsung buka galeri
                injectGalleryInterceptorScript()
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                // Tampilkan error layout jika gagal memuat halaman utama
                if (failingUrl == TARGET_URL) {
                    errorLayout.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Izinkan navigasi internal
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // Intent eksternal (wa, tel, mailto)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    return true
                }
            }
        }

        // Setup WebChromeClient: Pengalihan Kamera -> Galeri
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }

            /**
             * Inti Fitur: Mengintersepsi setiap pemanggilan File Chooser / Kamera
             * dan MENGARAHKAN LANGSUNG KE GALERI HP tanpa membuka kamera.
             */
            override fun onShowFileChooser(
                mWebView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Batalkan callback lama jika ada
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                // Buka pemilih foto sistem tanpa meminta izin galeri secara luas.
                openGalleryPicker()
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        webView.loadUrl(request?.url.toString())
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    /**
     * Membuka pemilih foto Android tanpa meminta izin membaca seluruh galeri.
     * ACTION_OPEN_DOCUMENT memberikan akses ke URI yang dipilih pengguna.
     */
    private fun openGalleryPicker() {
        try {
            val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            galleryLauncher.launch(pickerIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                galleryLauncher.launch(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Tidak dapat membuka galeri: " + ex.message, Toast.LENGTH_LONG).show()
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka galeri: " + e.message, Toast.LENGTH_LONG).show()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    /**
     * Script JavaScript yang diinjeksi ke halaman web:
     * 1. Menghapus atribut 'capture="camera"' pada semua elemen <input type="file">
     *    sehingga browser tidak memaksa hardware kamera.
     * 2. Mengamati perubahan DOM agar input file yang muncul dinamis
     *    (seperti popup atau modal upload) otomatis diarahkan ke galeri.
     */
    private fun injectGalleryInterceptorScript() {
        val jsCode = """
            (function() {
                // 1. Fungsi untuk menetralkan input file
                function fixFileInputs() {
                    const inputs = document.querySelectorAll('input[type="file"]');
                    inputs.forEach(input => {
                        // Hapus atribut penangkap kamera bawaan
                        if (input.hasAttribute('capture')) {
                            input.removeAttribute('capture');
                        }
                        // Pastikan menerima gambar
                        if (!input.hasAttribute('accept') || input.getAttribute('accept') === '') {
                            input.setAttribute('accept', 'image/*');
                        }
                    });
                }

                // Jalankan saat ini
                fixFileInputs();

                // Pantau modal/form dinamis
                const observer = new MutationObserver(function(mutations) {
                    fixFileInputs();
                });
                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true
                });

                console.log("[POP SIPGN] Penyesuaian input file untuk pemilih galeri aktif.");
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.stopLoading()
        webView.clearHistory()
        super.onDestroy()
    }
}
