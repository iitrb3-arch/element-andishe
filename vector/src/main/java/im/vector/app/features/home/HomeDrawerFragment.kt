/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.observeK
import im.vector.app.core.extensions.replaceChildFragment
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.BuildMeta
import im.vector.app.databinding.FragmentHomeDrawerBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.spaces.SpaceListFragment
import im.vector.app.features.workers.signout.SignOutUiWorker
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class HomeDrawerFragment :
        VectorBaseFragment<FragmentHomeDrawerBinding>() {

    @Inject lateinit var session: Session
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var buildMeta: BuildMeta

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeDrawerBinding {
        return FragmentHomeDrawerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedActionViewModel = activityViewModelProvider.get(HomeSharedActionViewModel::class.java)

        if (savedInstanceState == null) {
            replaceChildFragment(R.id.homeDrawerGroupListContainer, SpaceListFragment::class.java)
        }
        session.userService().getUserLive(session.myUserId).observeK(viewLifecycleOwner) { optionalUser ->
            val user = optionalUser?.getOrNull()
            if (user != null) {
                avatarRenderer.render(user.toMatrixItem(), views.homeDrawerHeaderAvatarView)
                views.homeDrawerUsernameView.text = user.displayName
                views.homeDrawerUserIdView.text = user.userId
            }
        }
        // Profile
        views.homeDrawerHeader.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            navigator.openSettings(requireActivity(), directAccess = VectorSettingsActivity.EXTRA_DIRECT_ACCESS_GENERAL)
        }
        // Settings
        views.homeDrawerHeaderSettingsView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            navigator.openSettings(requireActivity())
        }
        // Sign out
        views.homeDrawerHeaderSignoutView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            SignOutUiWorker(requireActivity()).perform()
        }

        views.homeDrawerQRCodeButton.isVisible = false
        views.homeDrawerInviteFriendButton.isVisible = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        // Debug menu
        views.homeDrawerHeaderDebugView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            navigator.openDebug(requireActivity())
        }
    }

    override fun onResume() {
        super.onResume()
        views.homeDrawerHeaderDebugView.isVisible = buildMeta.isDebug && vectorPreferences.developerMode()
    }
}
