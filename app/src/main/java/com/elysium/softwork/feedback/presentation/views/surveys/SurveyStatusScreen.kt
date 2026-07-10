package com.elysium.softwork.feedback.presentation.views.surveys

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.softwork.R
import com.elysium.softwork.shared.presentation.components.SoftWorkButton
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky
import com.elysium.softwork.shared.presentation.theme.Warning
import com.elysium.softwork.shared.utils.discriminators.ButtonVariant
import com.elysium.softwork.shared.utils.values.SurveyStatusType

/**
 * Terminal status screen for a survey submission. The layout is driven entirely by
 * [statusType]:
 *  - [SurveyStatusType.SUCCESS] → a confirmation lockup (checkmark + success copy).
 *  - [SurveyStatusType.ALREADY_ANSWERED] → an informative warning that the worker has already
 *    submitted for this survey (the backend's unique-constraint `400`).
 *
 * Both states expose a single "back to surveys" action that pops to the pending-surveys list.
 *
 * @param statusType the outcome to render (resolved from the `status_type` nav argument).
 * @param onBack invoked by the action button to return to the surveys dashboard.
 */
@Composable
fun SurveyStatusScreen(
    statusType: SurveyStatusType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSuccess: Boolean = statusType == SurveyStatusType.SUCCESS
    val iconRes: Int = if (isSuccess) R.drawable.ic_check_circle else R.drawable.ic_flag
    val iconTint: Color = if (isSuccess) PrimarySky else Warning
    val titleRes: Int =
        if (isSuccess) R.string.survey_status_success_title else R.string.survey_status_already_title
    val subtitleRes: Int =
        if (isSuccess) R.string.survey_status_success_subtitle else R.string.survey_status_already_subtitle

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AccentWhite)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(titleRes),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(subtitleRes),
            color = AccentDark,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        SoftWorkButton(
            text = stringResource(R.string.survey_status_back_button),
            onClick = onBack,
            variant = ButtonVariant.EMPLOYEE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
