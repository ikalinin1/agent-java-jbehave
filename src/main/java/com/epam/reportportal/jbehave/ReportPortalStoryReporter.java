/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/agent-java-jbehave
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.epam.reportportal.jbehave;

import com.epam.reportportal.listeners.Statuses;
import org.jbehave.core.model.*;
import org.jbehave.core.reporters.NullStoryReporter;

import java.util.List;
import java.util.Map;

/**
 * JBehave Reporter for reporting results into ReportPortal. Requires using
 * {@link ReportPortalEmbeddable} or
 * {@link RpJUnitStories} as well
 * 
 * @author Andrei Varabyeu
 * 
 */
public class ReportPortalStoryReporter extends NullStoryReporter {

	@Override
	public void storyCancelled(Story story, StoryDuration storyDuration) {
		JBehaveUtils.finishStory();
	}

	@Override
	public void beforeStory(Story story, boolean givenStory) {
		JBehaveUtils.startStory(story, givenStory);
	}

	@Override
	public void afterStory(boolean givenStory) {
		JBehaveUtils.finishStory();
	}

	@Override
	public void beforeScenario(Scenario scenario) {
		JBehaveContext.getCurrentStory().setScenarioMeta(scenario.getMeta());
		JBehaveUtils.startScenario(scenario.getTitle());
	}

	@Override
	public void afterScenario() {
		JBehaveUtils.finishScenario();
	}

	@Override
	public void beforeStep(String step) {
		JBehaveUtils.startStep(step);
	}

	@Override
	public void example(Map<String, String> tableRow, int exampleIndex) {
		JBehaveContext.getCurrentStory().getExamples().setCurrentExample(exampleIndex);
	}

	@Override
	public void beforeExamples(List<String> steps, ExamplesTable table) {
		JBehaveContext.getCurrentStory().setExamples(new JBehaveContext.Examples(steps, table));
	}

	@Override
	public void afterExamples() {
		JBehaveContext.getCurrentStory().setExamples(null);
	}

	@Override
	public void successful(String step) {
		JBehaveUtils.finishStep();
	}

	@Override
	public void ignorable(String step) {
        if (JBehaveContext.getCurrentStory().getCurrentStep() == null) {
            JBehaveUtils.startStep(step);
        }
        JBehaveUtils.finishStep(Statuses.SKIPPED);
	}

	@Override
	public void notPerformed(String step) {
        if (JBehaveContext.getCurrentStory().getCurrentStep() == null) {
            JBehaveUtils.startStep(step);
        }
		JBehaveUtils.finishStep(Statuses.SKIPPED);
	}

	@Override
	public void failed(String step, Throwable cause) {
        JBehaveUtils.sendStackTraceToRP(cause);
        JBehaveUtils.finishStep(Statuses.FAILED);
	}

	@Override
	public void pending(String step) {
		simulateStep(step, Statuses.SKIPPED);
	}

	@Override
	public void scenarioNotAllowed(Scenario scenario, String filter) {
		if (null != scenario.getExamplesTable() && scenario.getExamplesTable().getRowCount() > 0) {
			beforeExamples(scenario.getSteps(), scenario.getExamplesTable());
			for (int i = 0; i < scenario.getExamplesTable().getRowCount(); i++) {
				example(scenario.getExamplesTable().getRow(i));
				for (String step : scenario.getSteps()) {
					simulateStep(step, Statuses.SKIPPED);
				}
			}
		} else {
			for (String step : scenario.getSteps()) {
				simulateStep(step, Statuses.SKIPPED);
			}
		}
		JBehaveUtils.finishScenario(Statuses.SKIPPED);
	}

	private void simulateStep(String step, String status) {
		JBehaveUtils.startStep(step);
		JBehaveUtils.finishStep(status);
	}

}
