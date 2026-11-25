package com.example.myreceipts

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ViewFlipper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myreceipts.data.AppPreferences
import com.example.myreceipts.data.AppDatabase
import com.example.myreceipts.data.AppRepository

class MainActivity : AppCompatActivity() {
    private companion object {
        const val SCREEN_WELCOME = 0
        const val SCREEN_ONBOARDING_RECEIPT = 1
        const val SCREEN_ONBOARDING_ANALYTICS = 2
        const val SCREEN_ONBOARDING_REMINDERS = 3
        const val SCREEN_HOME = 4
        const val SCREEN_LOGIN = 5
        const val SCREEN_REGISTER = 6
        const val STATE_SCREEN = "current_screen"
        const val INDICATOR_ANIMATION_MS = 90L
    }

    private lateinit var screens: ViewFlipper
    private lateinit var preferences: AppPreferences
    private lateinit var repository: AppRepository
    private var indicatorAnimator: ValueAnimator? = null
    private var isTransitioning = false
    private var loginReturnScreen = SCREEN_REGISTER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        repository = AppRepository(this)
        AppDatabase(this).initialize().close()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        screens = findViewById(R.id.screens)

        ViewCompat.setOnApplyWindowInsetsListener(screens) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }

        preferences.getSession()?.userId?.toLongOrNull()?.let(repository::getUser)?.let { user ->
            if (user.role == "admin" || user.role == "manager") {
                openStaffWorkspace(user.id)
                return
            }
        }

        val hasSession = preferences.getSession() != null
        val unauthenticatedScreen =
            if (preferences.onboardingCompleted) SCREEN_REGISTER else SCREEN_WELCOME
        val restoredScreen = savedInstanceState?.getInt(STATE_SCREEN)
        val initialScreen = when {
            hasSession -> SCREEN_HOME
            restoredScreen == SCREEN_HOME -> unauthenticatedScreen
            restoredScreen != null -> restoredScreen
            else -> unauthenticatedScreen
        }
        screens.displayedChild = initialScreen.coerceIn(SCREEN_WELCOME, SCREEN_REGISTER)
        screens.inAnimation = null
        screens.outAnimation = null

        val welcome = screens.getChildAt(SCREEN_WELCOME)
        val receiptOnboarding = screens.getChildAt(SCREEN_ONBOARDING_RECEIPT)
        val analyticsOnboarding = screens.getChildAt(SCREEN_ONBOARDING_ANALYTICS)
        val remindersOnboarding = screens.getChildAt(SCREEN_ONBOARDING_REMINDERS)
        val login = screens.getChildAt(SCREEN_LOGIN)
        val register = screens.getChildAt(SCREEN_REGISTER)

        setupPasswordToggle(
            login.findViewById(R.id.etPassword),
            login.findViewById(R.id.ivTogglePassword),
            R.string.login_label_password
        )
        setupPasswordToggle(
            register.findViewById(R.id.etPassword),
            register.findViewById(R.id.ivTogglePassword),
            R.string.register_label_password
        )

        welcome.findViewById<View>(R.id.btnCreateAccount).setOnClickListener {
            showScreen(SCREEN_ONBOARDING_RECEIPT)
        }
        welcome.findViewById<View>(R.id.btnHaveAccount).setOnClickListener {
            loginReturnScreen = SCREEN_WELCOME
            showScreen(SCREEN_LOGIN)
        }

        receiptOnboarding.findViewById<View>(R.id.btnNext).setOnClickListener {
            showNextOnboardingPage(1)
        }
        receiptOnboarding.findViewById<View>(R.id.btnSkip).setOnClickListener {
            finishOnboarding()
        }
        analyticsOnboarding.findViewById<View>(R.id.btnNext).setOnClickListener {
            showNextOnboardingPage(2)
        }
        analyticsOnboarding.findViewById<View>(R.id.btnSkip).setOnClickListener {
            finishOnboarding()
        }
        remindersOnboarding.findViewById<View>(R.id.btnStart).setOnClickListener {
            finishOnboarding()
        }
        remindersOnboarding.findViewById<View>(R.id.tvSkip).setOnClickListener {
            finishOnboarding()
        }

        login.findViewById<View>(R.id.tvCreateAccount)?.setOnClickListener {
            showScreen(SCREEN_REGISTER)
        }
        register.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            loginReturnScreen = SCREEN_REGISTER
            showScreen(SCREEN_LOGIN)
        }
        register.findViewById<View>(R.id.tvHaveAccount)?.setOnClickListener {
            loginReturnScreen = SCREEN_REGISTER
            showScreen(SCREEN_LOGIN)
        }
        login.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            showScreen(loginReturnScreen)
        }
        setupAuthentication(login, register)
        screens.findViewById<View>(R.id.btnCamera)?.setOnClickListener {
            startActivity(android.content.Intent(this, ScanReceiptActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTransitioning) return

                when (screens.displayedChild) {
                    SCREEN_REGISTER -> {
                        loginReturnScreen = SCREEN_REGISTER
                        showScreen(SCREEN_LOGIN)
                    }
                    SCREEN_LOGIN -> showScreen(loginReturnScreen)
                    SCREEN_ONBOARDING_ANALYTICS -> showPreviousOnboardingPage(0)
                    SCREEN_ONBOARDING_REMINDERS -> showPreviousOnboardingPage(1)
                    SCREEN_ONBOARDING_RECEIPT -> showScreen(SCREEN_WELCOME)
                    SCREEN_WELCOME,
                    SCREEN_HOME -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SCREEN, screens.displayedChild)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        indicatorAnimator?.cancel()
        super.onDestroy()
    }

    private fun setupAuthentication(login: View, register: View) {
        val session = preferences.getSession()
        if (session != null) populateHome(session.userId.toLongOrNull())

        login.findViewById<View>(R.id.btnLogin).setOnClickListener {
            val email = login.findViewById<EditText>(R.id.etEmail).text.toString()
            val password = login.findViewById<EditText>(R.id.etPassword).text.toString()

            val user = repository.authenticate(email, password)
            if (user == null) {
                Toast.makeText(
                    this,
                    "Неверный email или пароль",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            preferences.saveSession(user.id.toString(), user.email, user.role)
            if (user.role == "admin" || user.role == "manager") {
                openStaffWorkspace(user.id)
            } else {
                populateHome(user.id)
                showScreen(SCREEN_HOME)
            }
        }

        register.findViewById<View>(R.id.btnCreate).setOnClickListener {
            val name = register.findViewById<EditText>(R.id.etName).text.toString()
            val email = register.findViewById<EditText>(R.id.etEmail).text.toString()
            val password = register.findViewById<EditText>(R.id.etPassword).text.toString()

            try {
                val user = repository.registerResident(name, email, password)
                preferences.saveSession(user.id.toString(), user.email, user.role)
                populateHome(user.id)
                showScreen(SCREEN_HOME)
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "Не удалось создать аккаунт", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populateHome(userId: Long?) {
        val home = screens.findViewById<View>(R.id.cardHero)?.rootView ?: return
        val summary = userId?.let(repository::loadResidentSummary)

        home.findViewById<TextView>(R.id.tvAddress)?.text =
            summary?.address ?: getString(R.string.home_address)
        home.findViewById<TextView>(R.id.tvTitle)?.text =
            summary?.period?.let { "Квитанция за $it" } ?: getString(R.string.home_title)
        home.findViewById<TextView>(R.id.tvHeroAmount)?.text =
            summary?.let {
                val rubles = it.totalKop / 100
                val kopeks = it.totalKop % 100
                "$rubles ₽ ${"%02d".format(kopeks)}"
            } ?: getString(R.string.amount_total)
        home.findViewById<TextView>(R.id.tvDueChip)?.text =
            when (summary?.status) {
                "paid" -> "Оплачено"
                "unpaid" -> "К оплате"
                else -> getString(R.string.home_due_chip)
            }
    }

    private fun openStaffWorkspace(userId: Long) {
        startActivity(
            android.content.Intent(this, StaffMainActivity::class.java)
                .putExtra("staff_user_id", userId)
        )
        finish()
    }

    private fun showNextOnboardingPage(targetPage: Int) {
        animateIndicatorToPage(targetPage)
    }

    private fun showPreviousOnboardingPage(targetPage: Int) {
        animateIndicatorToPage(targetPage)
    }

    private fun animateIndicatorToPage(targetPage: Int) {
        if (isTransitioning) return

        val currentScreen = screens.displayedChild
        val currentPage = currentScreen - SCREEN_ONBOARDING_RECEIPT
        val targetScreen = SCREEN_ONBOARDING_RECEIPT + targetPage
        if (currentPage !in 0..2 || targetPage !in 0..2 || currentPage == targetPage) return

        val currentLayout = screens.getChildAt(currentScreen)
        val indicator = currentLayout.findViewById<LinearLayout>(R.id.pageIndicator)
            ?: currentLayout.findViewById(R.id.dots)
        val outgoingDot = indicator?.getChildAt(currentPage)
        val incomingDot = indicator?.getChildAt(targetPage)
        if (outgoingDot == null || incomingDot == null) {
            showScreen(targetScreen)
            return
        }

        isTransitioning = true
        val dotSize = resources.getDimensionPixelSize(R.dimen.dp_8)
        val activeSize = resources.getDimensionPixelSize(R.dimen.dp_26)
        val activeColor = getColor(R.color.mr_accent)
        val inactiveColor = getColor(R.color.mr_dot_inactive)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INDICATOR_ANIMATION_MS
            interpolator = LinearInterpolator()
            var cancelled = false
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                updateDot(
                    outgoingDot,
                    activeSize + ((dotSize - activeSize) * progress).toInt(),
                    activeColor
                )
                updateDot(
                    incomingDot,
                    dotSize + ((activeSize - dotSize) * progress).toInt(),
                    activeColor
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    indicatorAnimator = null
                    if (cancelled) return
                    isTransitioning = false
                    showScreen(targetScreen)
                }
            })
        }
        indicatorAnimator = animator
        animator.start()
    }

    private fun setIndicatorPage(indicator: LinearLayout, activePage: Int) {
        val dotSize = resources.getDimensionPixelSize(R.dimen.dp_8)
        val activeSize = resources.getDimensionPixelSize(R.dimen.dp_26)
        val activeColor = getColor(R.color.mr_accent)
        val inactiveColor = getColor(R.color.mr_dot_inactive)
        for (index in 0 until indicator.childCount) {
            updateDot(
                indicator.getChildAt(index),
                if (index == activePage) activeSize else dotSize,
                if (index == activePage) activeColor else inactiveColor
            )
        }
    }

    private fun updateDot(view: View, width: Int, color: Int) {
        val params = view.layoutParams
        params.width = width
        view.layoutParams = params
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.dp_4)
            setColor(color)
        }
    }

    private fun setupPasswordToggle(
        passwordField: EditText,
        toggle: ImageView,
        passwordLabel: Int
    ) {
        passwordField.transformationMethod = PasswordTransformationMethod.getInstance()
        toggle.isClickable = true
        toggle.isFocusable = true
        toggle.contentDescription = getString(
            R.string.cd_show_password,
            getString(passwordLabel)
        )
        toggle.setOnClickListener {
            val isVisible = passwordField.transformationMethod !is HideReturnsTransformationMethod
            val selection = passwordField.selectionStart
            passwordField.transformationMethod = if (isVisible) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            passwordField.setSelection(selection.coerceIn(0, passwordField.length()))
            toggle.setImageResource(
                if (isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
            )
            toggle.contentDescription = getString(
                if (isVisible) R.string.cd_hide_password else R.string.cd_show_password,
                getString(passwordLabel)
            )
        }
    }

    private fun finishOnboarding() {
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        isTransitioning = false
        preferences.onboardingCompleted = true
        showScreen(if (preferences.getSession() != null) SCREEN_HOME else SCREEN_REGISTER)
    }

    private fun showScreen(screen: Int) {
        if (screen in 0 until screens.childCount) {
            val page = screen - SCREEN_ONBOARDING_RECEIPT
            if (page in 0..2) {
                val layout = screens.getChildAt(screen)
                val indicator = layout.findViewById<LinearLayout>(R.id.pageIndicator)
                    ?: layout.findViewById(R.id.dots)
                indicator?.let { setIndicatorPage(it, page) }
            }
            screens.displayedChild = screen
        }
    }
}
