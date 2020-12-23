/*
 * Copyright (C) 2020 Epic Games, Inc. All Rights Reserved.
 */

package com.epam.reportportal.jbehave.status;

import com.epam.reportportal.jbehave.BaseTest;
import com.epam.reportportal.jbehave.ReportPortalStepFormat;
import com.epam.reportportal.jbehave.integration.basic.EmptySteps;
import com.epam.reportportal.jbehave.integration.basic.FailedSteps;
import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.ReportPortalClient;
import com.epam.reportportal.util.test.CommonUtils;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

public class ItemStatusFailedPassedTest extends BaseTest {

	private final String storyId = CommonUtils.namedId("story_");
	private final String scenarioId = CommonUtils.namedId("scenario_");
	private final List<String> stepIds = Stream.generate(() -> CommonUtils.namedId("step_")).limit(2).collect(Collectors.toList());

	private final ReportPortalClient client = mock(ReportPortalClient.class);
	private final ReportPortalStepFormat format = new ReportPortalStepFormat(ReportPortal.create(client, standardParameters()));

	@BeforeEach
	public void setupMock() {
		mockLaunch(client, null, storyId, scenarioId, stepIds);
		mockBatchLogging(client);
	}

	private static final String FAILED_PASSED_SCENARIO_PATH = "stories/status/FailedPassedScenario.story";

	@Test
	public void verify_a_step_failed_and_a_step_passed_parent_status_calculated() {
		run(format, FAILED_PASSED_SCENARIO_PATH, new FailedSteps(), new EmptySteps());

		verify(client).startTestItem(any());
		verify(client).startTestItem(same(storyId), any());
		verify(client, times(2)).startTestItem(same(scenarioId), any());

		ArgumentCaptor<FinishTestItemRQ> finishCaptor = ArgumentCaptor.forClass(FinishTestItemRQ.class);
		verify(client).finishTestItem(same(stepIds.get(0)), finishCaptor.capture());
		verify(client).finishTestItem(same(stepIds.get(1)), finishCaptor.capture());
		verify(client).finishTestItem(same(scenarioId), finishCaptor.capture());
		verify(client).finishTestItem(same(storyId), finishCaptor.capture());

		List<FinishTestItemRQ> finishItems = finishCaptor.getAllValues();
		FinishTestItemRQ stepOneFinish = finishItems.get(0);
		assertThat(stepOneFinish.getStatus(), equalTo(ItemStatus.FAILED.name()));

		FinishTestItemRQ stepTwoFinish = finishItems.get(1);
		assertThat(stepTwoFinish.getStatus(), equalTo(ItemStatus.SKIPPED.name()));

		FinishTestItemRQ scenarioFinish = finishItems.get(2);
		assertThat(scenarioFinish.getStatus(), equalTo(ItemStatus.FAILED.name()));

		FinishTestItemRQ storyFinish = finishItems.get(3);
		assertThat(storyFinish.getStatus(), equalTo(ItemStatus.FAILED.name()));
	}
}
