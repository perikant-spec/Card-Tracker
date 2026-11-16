package tracker.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tracker.app.AppGraph

private const val NEW_STATEMENT = "new"

/** Routes: dashboard, card/{cardId}, statement/{cardId}/{proposalId|new}, review, settings. */
@Composable
fun AppNav(graph: AppGraph, suppressRelock: (Boolean) -> Unit) {
    val nav = rememberNavController()
    val repo = graph.repository

    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") {
            val vm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(repo) } })
            DashboardScreen(
                viewModel = vm,
                onCard = { cardId -> nav.navigate("card/$cardId") },
                onReview = { nav.navigate("review") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("card/{cardId}") { entry ->
            val cardId = entry.arguments?.getString("cardId") ?: ""
            val vm: CardDetailViewModel = viewModel(factory = viewModelFactory { initializer { CardDetailViewModel(repo, cardId) } })
            CardDetailScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onStatement = { proposalId -> nav.navigate("statement/$cardId/" + (proposalId ?: NEW_STATEMENT)) },
            )
        }
        composable("statement/{cardId}/{proposalId}") { entry ->
            val cardId = entry.arguments?.getString("cardId") ?: ""
            val proposalId = entry.arguments?.getString("proposalId")?.takeIf { it != NEW_STATEMENT }
            val vm: StatementViewModel = viewModel(factory = viewModelFactory { initializer { StatementViewModel(repo, cardId, proposalId) } })
            StatementScreen(viewModel = vm, onBack = { nav.popBackStack() }, onReview = { nav.navigate("review") })
        }
        composable("review") {
            val vm: ReviewViewModel = viewModel(factory = viewModelFactory { initializer { ReviewViewModel(repo) } })
            ReviewScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(repo, graph.backfill, graph.scope) } },
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpeningStatement = { cardId -> nav.navigate("statement/$cardId/$NEW_STATEMENT") },
                suppressRelock = suppressRelock,
            )
        }
    }
}
