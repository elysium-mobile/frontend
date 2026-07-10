package com.elysium.softwork.feedback.presentation.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.elysium.softwork.feedback.presentation.views.chat.AiChatScreen
import com.elysium.softwork.feedback.presentation.views.surveys.PendingSurveysScreen
import com.elysium.softwork.feedback.presentation.views.surveys.SurveyStatusScreen
import com.elysium.softwork.feedback.presentation.views.surveys.TakeSurveyScreen
import com.elysium.softwork.shared.presentation.navigation.PushEnter
import com.elysium.softwork.shared.presentation.navigation.PushExit
import com.elysium.softwork.shared.presentation.navigation.PushPopEnter
import com.elysium.softwork.shared.presentation.navigation.PushPopExit
import com.elysium.softwork.shared.utils.values.SurveyStatusType

/**
 * Registers the Feedback routes inside an existing [NavGraphBuilder]. Invoked from the host
 * `NavHost` (typically `MainNavHost`) so the surveys destination lives on the same back stack
 * as the rest of the authenticated shell.
 *
 * Route catalog lives in [FeedbackRoutes].
 *
 * @param navController controller used to build navigate / popBackStack lambdas.
 */
fun NavGraphBuilder.feedbackGraph(navController: NavHostController) {
    composable(
        route = FeedbackRoutes.PENDING_SURVEYS,
        enterTransition = PushEnter,
        exitTransition = PushExit,
        popEnterTransition = PushPopEnter,
        popExitTransition = PushPopExit,
    ) {
        PendingSurveysScreen(
            onBack = { navController.popBackStack() },
            onStartSurvey = { surveyId ->
                surveyId?.let { navController.navigate(FeedbackRoutes.takeSurvey(it)) }
            },
        )
    }

    composable(
        route = FeedbackRoutes.TAKE_SURVEY,
        arguments = listOf(
            navArgument(FeedbackRoutes.ARG_SURVEY_ID) { type = NavType.LongType },
        ),
        enterTransition = PushEnter,
        exitTransition = PushExit,
        popEnterTransition = PushPopEnter,
        popExitTransition = PushPopExit,
    ) { backStackEntry ->
        val surveyId: Long = backStackEntry.arguments?.getLong(FeedbackRoutes.ARG_SURVEY_ID) ?: 0L
        TakeSurveyScreen(
            surveyId = surveyId,
            onBack = { navController.popBackStack() },
            // Route to the status screen on either terminal outcome (201 success or the
            // already-answered 400), removing the take-survey entry from the back stack so
            // "back to surveys" lands on the pending list rather than the filled-out form.
            onCompleted = { statusType ->
                navController.navigate(FeedbackRoutes.surveyStatus(statusType.key)) {
                    popUpTo(FeedbackRoutes.TAKE_SURVEY) { inclusive = true }
                }
            },
        )
    }

    composable(
        route = FeedbackRoutes.SURVEY_STATUS,
        arguments = listOf(
            navArgument(FeedbackRoutes.ARG_STATUS_TYPE) { type = NavType.StringType },
        ),
        enterTransition = PushEnter,
        exitTransition = PushExit,
        popEnterTransition = PushPopEnter,
        popExitTransition = PushPopExit,
    ) { backStackEntry ->
        val statusType: SurveyStatusType = SurveyStatusType.fromKey(
            backStackEntry.arguments?.getString(FeedbackRoutes.ARG_STATUS_TYPE),
        )
        SurveyStatusScreen(
            statusType = statusType,
            // Pops the status screen; with take-survey already cleared, this lands on the
            // pending-surveys list.
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = FeedbackRoutes.AI_CHAT,
        enterTransition = PushEnter,
        exitTransition = PushExit,
        popEnterTransition = PushPopEnter,
        popExitTransition = PushPopExit,
    ) {
        AiChatScreen(onBack = { navController.popBackStack() })
    }
}
