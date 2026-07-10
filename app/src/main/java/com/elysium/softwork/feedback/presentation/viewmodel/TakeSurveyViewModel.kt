package com.elysium.softwork.feedback.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.feedback.application.usecase.GetSurveyQuestionsUseCase
import com.elysium.softwork.feedback.application.usecase.SubmitSurveyResponseUseCase
import com.elysium.softwork.feedback.domain.model.QuestionSurvey
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.utils.values.SurveyQuestionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the take-survey screen.
 *
 * Loads the question set for a survey (filtered client-side by `survey_id`), buffers one
 * answer per question keyed by `question_survey_id`, and submits an aggregated
 * [com.elysium.softwork.feedback.domain.model.SurveyResponse] through
 * [SubmitSurveyResponseUseCase].
 *
 * **Answer folding.** The backend `survey-responses` contract has no per-question answer
 * array — only `commentary` + `cause` (plus `survey_id` / `employee_profile_id` /
 * `submitted_at`). The per-question answers collected in the UI are therefore folded into
 * those two fields on submit: every question and its answer is written into [commentary] as a
 * readable multiline block, while the categorical answers (RATING + MULTIPLE_CHOICE) are
 * summarised into [cause]. This is a pragmatic mapping dictated by the endpoint shape; if the
 * backend later exposes a per-question answer resource, submission moves there without touching
 * the screen.
 *
 * @param getSurveyQuestions fetches + filters the questions for the active survey.
 * @param submitSurveyResponse assembles identity/date and POSTs the response.
 */
class TakeSurveyViewModel(
    private val getSurveyQuestions: GetSurveyQuestionsUseCase,
    private val submitSurveyResponse: SubmitSurveyResponseUseCase,
) : ViewModel() {

    private val _questions: MutableStateFlow<List<QuestionSurvey>> = MutableStateFlow(emptyList())

    /** The questions belonging to the active survey (already filtered by `survey_id`). */
    val questions: StateFlow<List<QuestionSurvey>> = _questions.asStateFlow()

    private val _answers: MutableStateFlow<Map<Long, String>> = MutableStateFlow(emptyMap())

    /** Current answers, keyed by `question_survey_id`; the value's meaning depends on the type. */
    val answers: StateFlow<Map<Long, String>> = _answers.asStateFlow()

    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSubmitting: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** `true` while the submission round-trip is in flight; gates the submit button. */
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _submitted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Flips `true` once a submission is confirmed (HTTP 201); the screen pops on this signal. */
    val submitted: StateFlow<Boolean> = _submitted.asStateFlow()

    private var currentSurveyId: Long = 0L

    /** Loads (or reloads) the question set for [surveyId]. Idempotent per screen entry. */
    fun load(surveyId: Long) {
        currentSurveyId = surveyId
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            getSurveyQuestions(surveyId)
                .onSuccess { _questions.value = it }
                .onFailure { _errorMessage.value = resolveError(it) }
            _isLoading.value = false
        }
    }

    /** Records the worker's [value] for the question identified by [questionId]. */
    fun setAnswer(questionId: Long, value: String) {
        _answers.value = _answers.value + (questionId to value)
    }

    /**
     * `true` when every loaded question has a non-blank answer captured — the submit gate.
     * An empty question set is not submittable.
     */
    fun isComplete(): Boolean {
        val questions = _questions.value
        if (questions.isEmpty()) return false
        val answers = _answers.value
        return questions.all { question ->
            val key: Long = question.question_survey_id ?: return@all false
            !answers[key].isNullOrBlank()
        }
    }

    /**
     * Folds the per-question answers into `commentary` / `cause` and submits. No-ops while a
     * submission is in flight or the form is incomplete. On success the form is cleared and
     * [submitted] flips so the screen can pop.
     */
    fun submit() {
        if (_isSubmitting.value || !isComplete()) return
        _isSubmitting.value = true
        _errorMessage.value = null

        val questions = _questions.value
        val answers = _answers.value

        val commentary: String = questions.joinToString(separator = "\n") { question ->
            val answer: String = answers[question.question_survey_id ?: 0L].orEmpty()
            "${question.text_question.orEmpty()}: $answer"
        }
        val cause: String = questions
            .filter { SurveyQuestionType.fromKey(it.question_type) != SurveyQuestionType.OPEN_SURVEY }
            .mapNotNull { answers[it.question_survey_id ?: 0L]?.takeIf { a -> a.isNotBlank() } }
            .joinToString(separator = "; ")
            .ifBlank { DEFAULT_CAUSE }

        viewModelScope.launch {
            submitSurveyResponse(surveyId = currentSurveyId, commentary = commentary, cause = cause)
                .onSuccess {
                    // HTTP 201 confirmed: invalidate both the captured answers (form input) and
                    // the loaded question set (question state) before signalling the pop. The
                    // `submitted` latch drives the navigation pop; the screen gates its empty-state
                    // on `!submitted` so clearing the questions here never flashes the "no
                    // questions" placeholder on the outgoing frame.
                    _answers.value = emptyMap()
                    _questions.value = emptyList()
                    _submitted.value = true
                }
                .onFailure { _errorMessage.value = resolveError(it) }
            _isSubmitting.value = false
        }
    }

    /** Clears the surfaced error after the UI has shown it. */
    fun consumeError() {
        _errorMessage.value = null
    }

    /** Resets the [submitted] latch after the screen has consumed the pop signal. */
    fun consumeSubmitted() {
        _submitted.value = false
    }

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val GENERIC_ERROR: String = "Unexpected error"

        /** Fallback `cause` when a survey has no categorical (rating / choice) questions. */
        private const val DEFAULT_CAUSE: String = "GENERAL"

        /** Factory that assembles the use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val locator = application.serviceLocator
                return TakeSurveyViewModel(
                    getSurveyQuestions = GetSurveyQuestionsUseCase(locator.surveyStore),
                    submitSurveyResponse = SubmitSurveyResponseUseCase(
                        store = locator.surveyStore,
                        prefs = locator.sharedPrefsManager,
                    ),
                ) as T
            }
        }
    }
}
