/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.airbnb.mvrx.viewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.POP_BACK_STACK_EXCLUSIVE
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.extensions.addFragmentToBackstack
import im.vector.app.core.extensions.validateBackPressed
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.onboarding.AuthenticationDescription
import im.vector.app.features.pin.UnlockedActivity
import im.vector.app.features.login.SignMode
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.auth.SSOAction
import org.matrix.android.sdk.api.extensions.tryOrNull

/**
 * The LoginActivity manages the fragment navigation and also display the loading View.
 */
@AndroidEntryPoint
open class LoginActivity : VectorBaseActivity<ActivityLoginBinding>(), UnlockedActivity {

    private val loginViewModel: LoginViewModel by viewModel()

    private val enterAnim = R.anim.enter_fade_in
    private val exitAnim = R.anim.exit_fade_out

    private val popEnterAnim = R.anim.no_anim
    private val popExitAnim = R.anim.exit_fade_out

    private val topFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(views.loginFragmentContainer.id)

    private val commonOption: (FragmentTransaction) -> Unit = { ft ->
        // Find the loginLogo on the current Fragment, this should not return null
        (topFragment?.view as? ViewGroup)
                // Find findViewById does not work, I do not know why
                // findViewById<View?>(R.id.loginLogo)
                ?.children
                ?.firstOrNull { it.id == im.vector.lib.ui.styles.R.id.loginLogo }
                ?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
        ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
    }

    final override fun getBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        analyticsScreenName = MobileScreen.ScreenName.Login

        if (isFirstCreation()) {
            addFirstFragment()
        }

        loginViewModel.onEach {
            updateWithState(it)
        }

        loginViewModel.observeViewEvents { handleLoginViewEvents(it) }

        // Get config extra
        val loginConfig = intent.getParcelableExtraCompat<LoginConfig?>(EXTRA_CONFIG)
        if (isFirstCreation()) {
            loginViewModel.handle(LoginAction.InitWith(loginConfig))
            loginViewModel.handle(LoginAction.UpdateHomeServer(getString(im.vector.app.config.R.string.matrix_org_server_url)))
        }
    }

    protected open fun addFirstFragment() {
        addFragment(views.loginFragmentContainer, LoginFragment::class.java, tag = FRAGMENT_LOGIN_TAG)
    }

    private fun handleLoginViewEvents(loginViewEvents: LoginViewEvents) {
        when (loginViewEvents) {
            is LoginViewEvents.OutdatedHomeserver -> {
                MaterialAlertDialogBuilder(this)
                        .setTitle(CommonStrings.login_error_outdated_homeserver_title)
                        .setMessage(CommonStrings.login_error_outdated_homeserver_warning_content)
                        .setPositiveButton(CommonStrings.ok, null)
                        .show()
                Unit
            }
            is LoginViewEvents.OpenServerSelection -> Unit
            is LoginViewEvents.OnServerSelectionDone -> Unit
            is LoginViewEvents.OnSignModeSelected -> onSignModeSelected(loginViewEvents)
            is LoginViewEvents.OnLoginFlowRetrieved ->
                loginViewModel.handle(LoginAction.UpdateSignMode(SignMode.SignIn))
            is LoginViewEvents.OnWebLoginError -> onWebLoginError(loginViewEvents)
            is LoginViewEvents.Failure,
            is LoginViewEvents.Loading ->
                // This is handled by the Fragments
                Unit
        }
    }

    private fun updateWithState(loginViewState: LoginViewState) {
        if (loginViewState.isUserLogged()) {
            if (loginViewState.signMode == SignMode.SignUp) {
                // change the screen name
                analyticsScreenName = MobileScreen.ScreenName.Register
            }
            val authDescription = inferAuthDescription(loginViewState)
            val intent = HomeActivity.newIntent(this, firstStartMainActivity = false, authenticationDescription = authDescription)
            startActivity(intent)
            finish()
            return
        }

        // Loading
        views.loginLoading.isVisible = loginViewState.isLoading()
    }

    private fun inferAuthDescription(loginViewState: LoginViewState) = when (loginViewState.signMode) {
        SignMode.Unknown -> null
        SignMode.SignUp -> AuthenticationDescription.Register(type = AuthenticationDescription.AuthenticationType.Other)
        SignMode.SignIn -> AuthenticationDescription.Login
        SignMode.SignInWithMatrixId -> AuthenticationDescription.Login
    }

    private fun onWebLoginError(onWebLoginError: LoginViewEvents.OnWebLoginError) {
        // Pop the backstack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // And inform the user
        MaterialAlertDialogBuilder(this)
                .setTitle(CommonStrings.dialog_title_error)
                .setMessage(getString(CommonStrings.login_sso_error_message, onWebLoginError.description, onWebLoginError.errorCode))
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    private fun onSignModeSelected(loginViewEvents: LoginViewEvents.OnSignModeSelected) = withState(loginViewModel) { state ->
        // state.signMode could not be ready yet. So use value from the ViewEvent
        when (loginViewEvents.signMode) {
            SignMode.Unknown -> error("Sign mode has to be set before calling this method")
            SignMode.SignUp -> {
                // This is managed by the LoginViewEvents
            }
            SignMode.SignIn -> {
                // It depends on the LoginMode
                when (state.loginMode) {
                    LoginMode.Unknown -> error("Developer error")
                    is LoginMode.Sso -> launchSsoFlow()
                    is LoginMode.SsoAndPassword,
                    LoginMode.Password -> ensureLoginFragment()
                    LoginMode.Unsupported -> onLoginModeNotSupported(state.loginModeSupportedTypes)
                }
            }
            SignMode.SignInWithMatrixId -> addFragmentToBackstack(
                    views.loginFragmentContainer,
                    LoginFragment::class.java,
                    tag = FRAGMENT_LOGIN_TAG,
                    option = commonOption
            )
        }
    }

    private fun ensureLoginFragment() {
        val existing = supportFragmentManager.findFragmentByTag(FRAGMENT_LOGIN_TAG)
        if (existing == null) {
            addFragmentToBackstack(
                    views.loginFragmentContainer,
                    LoginFragment::class.java,
                    tag = FRAGMENT_LOGIN_TAG,
                    option = commonOption
            )
        }
    }

    private fun launchSsoFlow() = withState(loginViewModel) { state ->
        loginViewModel.getSsoUrl(
                redirectUrl = SSORedirectRouterActivity.VECTOR_REDIRECT_URL,
                deviceId = state.deviceId,
                providerId = null,
                action = SSOAction.LOGIN
        )?.let { ssoUrl ->
            openUrlInChromeCustomTab(this, null, ssoUrl)
        }
    }

    /**
     * Handle the SSO redirection here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        intent.data
                ?.let { tryOrNull { it.getQueryParameter("loginToken") } }
                ?.let { loginViewModel.handle(LoginAction.LoginWithToken(it)) }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        validateBackPressed {
            super.onBackPressed()
        }
    }

    private fun onLoginModeNotSupported(supportedTypes: List<String>) {
        MaterialAlertDialogBuilder(this)
                .setTitle(buildMeta.applicationName)
                .setMessage(getString(CommonStrings.login_mode_not_supported, supportedTypes.joinToString { "'$it'" }))
                .setPositiveButton(CommonStrings.yes) { _, _ ->
                    addFragmentToBackstack(
                            views.loginFragmentContainer,
                            LoginWebFragment::class.java,
                            option = commonOption
                    )
                }
                .setNegativeButton(CommonStrings.no, null)
                .show()
    }

    companion object {
        private const val FRAGMENT_LOGIN_TAG = "FRAGMENT_LOGIN_TAG"

        private const val EXTRA_CONFIG = "EXTRA_CONFIG"

        fun newIntent(context: Context, loginConfig: LoginConfig?): Intent {
            return Intent(context, LoginActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, loginConfig)
            }
        }

        fun redirectIntent(context: Context, data: Uri?): Intent {
            return Intent(context, LoginActivity::class.java).apply {
                setData(data)
            }
        }
    }
}
