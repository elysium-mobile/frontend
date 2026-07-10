package com.elysium.softwork.feedback.presentation.views.surveys

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.feedback.domain.model.QuestionSurvey
import com.elysium.softwork.feedback.presentation.viewmodel.TakeSurveyViewModel
import com.elysium.softwork.shared.presentation.components.SoftWorkButton
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.components.SoftWorkTextField
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentMint
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky
import com.elysium.softwork.shared.utils.discriminators.ButtonVariant
import com.elysium.softwork.shared.utils.values.SurveyQuestionType

/**
 * Answer-a-survey screen.
 *
 * Loads the questions for [surveyId] (fetched from `GET /api/v1/question-surveys`, filtered
 * client-side by `survey_id`) and renders one input per question, chosen by the resolved
 * [SurveyQuestionType]: a 1–5 selection for `RATING`, a text field for `OPEN_SURVEY`,
 * single-select rows for `MULTIPLE_CHOICE`, and a text-field fallback for any unknown type.
 *
 * The bottom submit button is enabled only once **every** visible question has captured a
 * non-blank answer. On a confirmed submission the ViewModel flips its `submitted` latch and this
 * screen invokes [onSubmitted] to pop back to the pending-surveys list.
 *
 * @param surveyId backend `survey_id` passed as the navigation argument.
 * @param onBack pops the screen (abandons the in-progress answers).
 * @param onSubmitted invoked once the response is stored (HTTP 201) so the host can pop.
 */
@Composable
fun TakeSurveyScreen(
    surveyId: Long,
    onBack: () -> Unit,
    onSubmitted: () -> Unit,
    viewModel: TakeSurveyViewModel = viewModel(factory = TakeSurveyViewModel.Factory),
) {
    val questions: List<QuestionSurvey> by viewModel.questions.collectAsStateWithLifecycle()
    val answers: Map<Long, String> by viewModel.answers.collectAsStateWithLifecycle()
    val isSubmitting: Boolean by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()
    val submitted: Boolean by viewModel.submitted.collectAsStateWithLifecycle()

    LaunchedEffect(surveyId) { viewModel.load(surveyId) }

    LaunchedEffect(submitted) {
        if (submitted) {
            onSubmitted()
            viewModel.consumeSubmitted()
        }
    }

    // Enabled only when every question has a non-blank answer — mirrors the ViewModel's guard so
    // the disabled button and the no-op submit stay in lockstep.
    val canSubmit: Boolean = questions.isNotEmpty() &&
        questions.all { !answers[it.question_survey_id ?: 0L].isNullOrBlank() } &&
        !isSubmitting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        TakeSurveyHeader(onBack = onBack)

        errorMessage?.let { message ->
            Text(
                text = message,
                color = Danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (questions.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.take_survey_empty),
                        color = AccentDark,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            items(items = questions, key = { it.question_survey_id ?: 0L }) { question ->
                val key: Long = question.question_survey_id ?: 0L
                QuestionCard(
                    question = question,
                    answer = answers[key].orEmpty(),
                    onAnswer = { viewModel.setAnswer(key, it) },
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            SoftWorkButton(
                text = stringResource(R.string.survey_submit_button),
                onClick = viewModel::submit,
                enabled = canSubmit,
                variant = ButtonVariant.EMPLOYEE,
            )
        }
    }
}

@Composable
private fun TakeSurveyHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = PrimaryNavy,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack),
        )
        Text(
            text = stringResource(R.string.take_survey_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
        )
    }
}

/**
 * One question card: the question text plus the answer input chosen by [SurveyQuestionType].
 * Unknown types fall back to the free-form text input so an answer is always capturable.
 */
@Composable
private fun QuestionCard(
    question: QuestionSurvey,
    answer: String,
    onAnswer: (String) -> Unit,
) {
    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = question.text_question.orEmpty(),
                color = PrimaryNavy,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (SurveyQuestionType.fromKey(question.question_type)) {
                SurveyQuestionType.RATING -> RatingInput(answer = answer, onAnswer = onAnswer)
                SurveyQuestionType.MULTIPLE_CHOICE -> ChoiceInput(answer = answer, onAnswer = onAnswer)
                // OPEN_SURVEY and any unknown/absent type render a free-form field.
                else -> OpenInput(answer = answer, onAnswer = onAnswer)
            }
        }
    }
}

/** 1–5 numeric selection row. The answer is stored as the selected number's string. */
@Composable
private fun RatingInput(answer: String, onAnswer: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..RATING_MAX).forEach { value ->
            val label: String = value.toString()
            val selected: Boolean = answer == label
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (selected) PrimarySky else AccentMint,
                        shape = CircleShape,
                    )
                    .clickable { onAnswer(label) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) AccentWhite else PrimaryNavy,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Free-form text answer. */
@Composable
private fun OpenInput(answer: String, onAnswer: (String) -> Unit) {
    SoftWorkTextField(
        value = answer,
        onValueChange = onAnswer,
        label = stringResource(R.string.take_survey_open_hint),
    )
}

/**
 * Single-select option rows. The backend `QuestionSurvey` carries no per-question option list,
 * so a fixed default agreement set is offered; the selected option's localized label is stored
 * as the answer.
 */
@Composable
private fun ChoiceInput(answer: String, onAnswer: (String) -> Unit) {
    val options: List<String> = listOf(
        stringResource(R.string.survey_choice_agree),
        stringResource(R.string.survey_choice_neutral),
        stringResource(R.string.survey_choice_disagree),
    )
    Column {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAnswer(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = answer == option,
                    onClick = { onAnswer(option) },
                    colors = RadioButtonDefaults.colors(selectedColor = PrimarySky),
                )
                Text(
                    text = option,
                    color = AccentDark,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Number of steps offered by the [RatingInput] selector (1..RATING_MAX). */
private const val RATING_MAX: Int = 5
