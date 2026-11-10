package dev.steamshelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.steamshelf.AppContainer
import dev.steamshelf.ui.detail.GameDetailScreen
import dev.steamshelf.ui.detail.GameDetailViewModel
import dev.steamshelf.ui.library.LibraryScreen
import dev.steamshelf.ui.library.LibraryViewModel
import dev.steamshelf.ui.login.LoginScreen
import dev.steamshelf.ui.login.LoginViewModel
import dev.steamshelf.ui.settings.SettingsScreen
import dev.steamshelf.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable object LoginRoute
@Serializable object LibraryRoute
@Serializable data class GameRoute(val appId: Int)
@Serializable object SettingsRoute

private sealed interface SessionState {
    data object Loading : SessionState
    data class Ready(val signedIn: Boolean) : SessionState
}

@Composable
fun AppNavHost(container: AppContainer) {
    val session by produceState<SessionState>(SessionState.Loading) {
        container.settings.account.collect { value = SessionState.Ready(signedIn = it != null) }
    }
    when (val s = session) {
        SessionState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        is SessionState.Ready -> NavGraph(container, s.signedIn)
    }
}

@Composable
private fun NavGraph(container: AppContainer, signedIn: Boolean) {
    val nav = rememberNavController()
    val startSignedIn = remember { signedIn }
    var lastSignedIn by remember { mutableStateOf(signedIn) }

    // Signing in or out swaps the whole back stack.
    LaunchedEffect(signedIn) {
        if (signedIn == lastSignedIn) return@LaunchedEffect
        lastSignedIn = signedIn
        nav.navigate(if (signedIn) LibraryRoute else LoginRoute) {
            popUpTo(nav.graph.id) { inclusive = true }
        }
    }

    NavHost(navController = nav, startDestination = if (startSignedIn) LibraryRoute else LoginRoute) {
        composable<LoginRoute> {
            LoginScreen(
                viewModel {
                    LoginViewModel(container.httpClient, container.steamApi, container.settings, container.repository)
                },
            )
        }
        composable<LibraryRoute> {
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.repository, container.settings) },
                onOpenGame = { nav.navigate(GameRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<GameRoute> { entry ->
            val appId = entry.toRoute<GameRoute>().appId
            GameDetailScreen(
                viewModel = viewModel { GameDetailViewModel(appId, container.repository) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings, container.repository, container.steamApi) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
