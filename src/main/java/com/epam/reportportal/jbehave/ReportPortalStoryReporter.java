/*
 * Copyright 2021 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.jbehave;

import com.epam.reportportal.jbehave.util.ItemTreeUtils;
import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.listeners.ItemType;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.reportportal.service.tree.TestItemTree;
import com.epam.reportportal.utils.StatusEvaluation;
import com.epam.reportportal.utils.TestCaseIdUtils;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.OperationCompletionRS;
import com.epam.ta.reportportal.ws.model.ParameterResource;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.issue.Issue;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jbehave.core.model.*;
import org.jbehave.core.reporters.NullStoryReporter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * JBehave Reporter for reporting results into ReportPortal.
 *
 * @author Vadzim Hushchanskou
 */
public abstract class ReportPortalStoryReporter extends NullStoryReporter {
	public static final String CODE_REF = "CODE_REF";
	public static final String START_TIME = "START_TIME";
	public static final String PARAMETERS = "PARAMETERS";
	public static final String PARENT = "PARENT";
	public static final String START_REQUEST = "START_REQUEST";
	public static final String FINISH_REQUEST = "FINISH_REQUEST";

	private static final String CODE_REFERENCE_DELIMITER = "/";
	private static final String CODE_REFERENCE_ITEM_TYPE_DELIMITER = ":";
	private static final String PARAMETER_ITEMS_DELIMITER = ";";
	private static final String CODE_REFERENCE_ITEM_START = "[";
	private static final String CODE_REFERENCE_ITEM_END = "]";
	private static final String EXAMPLE_PATTERN = "Example: %s";
	private static final String EXAMPLE_VALUE_PATTERN = "<%s>";
	private static final Pattern EXAMPLE_VALUE_MATCH = Pattern.compile("<([^>]*)>");
	private static final String EXAMPLE = "EXAMPLE";
	private static final String EXAMPLE_PARAMETER_DELIMITER = PARAMETER_ITEMS_DELIMITER + " ";
	private static final String EXAMPLE_KEY_VALUE_DELIMITER = CODE_REFERENCE_ITEM_TYPE_DELIMITER + " ";
	private static final String NO_NAME = "No name";
	private static final String BEFORE_STORIES = "BeforeStories";
	private static final String AFTER_STORIES = "AfterStories";
	private static final String BEFORE_STORY = "BeforeStory";
	private static final String AFTER_STORY = "AfterStory";

	private final Deque<Entity<?>> structure = new LinkedList<>();
	private final Map<String, TestItemTree.TestItemLeaf> currentStep = new HashMap<>();
	private final Supplier<Launch> launch;
	private final TestItemTree itemTree;
	private volatile ItemType currentLifecycleItemType;
	private volatile TestItemTree.TestItemLeaf lastStep;

	public ReportPortalStoryReporter(final Supplier<Launch> launchSupplier, TestItemTree testItemTree) {
		launch = launchSupplier;
		itemTree = testItemTree;
	}

	@Nonnull
	public Optional<TestItemTree.TestItemLeaf> getLastStep() {
		return ofNullable(lastStep);
	}

	private String getCodeRef(@Nullable final String parentCodeRef, @Nonnull final TestItemTree.ItemTreeKey key, ItemType type) {
		StringBuilder sb = new StringBuilder();
		if (isBlank(parentCodeRef)) {
			sb.append(key.getName());
		} else {
			sb.append(parentCodeRef)
					.append(CODE_REFERENCE_DELIMITER)
					.append(CODE_REFERENCE_ITEM_START)
					.append(type != ItemType.SUITE ? type.name() : EXAMPLE)
					.append(CODE_REFERENCE_ITEM_TYPE_DELIMITER)
					.append(key.getName().replace("\n", "").replace("\r", ""))
					.append(CODE_REFERENCE_ITEM_END);
		}
		return sb.toString();
	}

	protected String getStoryName(@Nonnull final Story story) {
		return story.getName();
	}

	@Nonnull
	protected Set<ItemAttributesRQ> getAttributes(@Nonnull final Meta meta) {
		Set<ItemAttributesRQ> items = new HashSet<>();
		meta.getPropertyNames().forEach(name -> {
			String value = meta.getProperty(name);
			items.add(isBlank(value) ? new ItemAttributesRQ(name) : new ItemAttributesRQ(name, value));
		});
		return items;
	}

	@Nonnull
	protected Set<ItemAttributesRQ> getAttributes(@Nonnull final Story story) {
		return getAttributes(story.getMeta());
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param story     JBehave story object
	 * @param codeRef   A story code reference
	 * @param startTime a story start time which will be passed to RP
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildStartStoryRq(@Nonnull Story story, @Nonnull String codeRef, @Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(getStoryName(story));
		rq.setCodeRef(codeRef);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(ItemType.STORY.name());
		rq.setAttributes(getAttributes(story));
		rq.setDescription(story.getDescription().asString());
		return rq;
	}

	protected String getScenarioName(Scenario scenario) {
		String title = scenario.getTitle();
		return isBlank(title) ? NO_NAME : title;
	}

	@Nonnull
	protected Set<ItemAttributesRQ> getAttributes(@Nonnull final Scenario scenario) {
		return getAttributes(scenario.getMeta());
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param scenario  JBehave scenario object
	 * @param codeRef   A scenario code reference
	 * @param startTime a scenario start time which will be passed to RP
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildStartScenarioRq(@Nonnull Scenario scenario, @Nonnull String codeRef, @Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(getScenarioName(scenario));
		rq.setCodeRef(codeRef);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(ItemType.SCENARIO.name());
		rq.setAttributes(getAttributes(scenario));
		return rq;
	}

	/**
	 * Extension point to customize example names
	 *
	 * @param example a map of variable name -> variable value
	 * @return a name of the given example
	 */
	protected String formatExampleName(@Nonnull final Map<String, String> example) {
		return String.format(EXAMPLE_PATTERN,
				example.entrySet()
						.stream()
						.map(e -> e.getKey() + EXAMPLE_KEY_VALUE_DELIMITER + e.getValue())
						.collect(Collectors.joining(EXAMPLE_PARAMETER_DELIMITER, CODE_REFERENCE_ITEM_START, CODE_REFERENCE_ITEM_END))
		);
	}

	@Nullable
	protected List<ParameterResource> getStepParameters(@Nullable final Map<String, String> params) {
		return ofNullable(params).map(p -> p.entrySet().stream().map(e -> {
			ParameterResource param = new ParameterResource();
			param.setKey(e.getKey());
			param.setValue(e.getValue());
			return param;
		}).collect(Collectors.toList())).orElse(null);
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param example   an example map
	 * @param codeRef   a step code reference
	 * @param startTime a step start time which will be passed to RP
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildStartExampleRq(@Nonnull final Map<String, String> example, @Nonnull String codeRef,
			@Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(formatExampleName(example));
		rq.setCodeRef(codeRef);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(ItemType.TEST.name());
		rq.setParameters(getStepParameters(example));
		return rq;
	}

	@Nonnull
	protected List<String> getUsedParameters(@Nonnull final String step) {
		Matcher m = EXAMPLE_VALUE_MATCH.matcher(step);
		List<String> result = new ArrayList<>();
		while (m.find()) {
			result.add(m.group(1));
		}
		return result;
	}

	@Nonnull
	protected String formatExampleStep(@Nonnull final String step, @Nullable final Map<String, String> example) {
		if (example == null) {
			return step;
		}
		String result = step;
		for (Map.Entry<String, String> e : example.entrySet()) {
			result = result.replaceAll(String.format(EXAMPLE_VALUE_PATTERN, e.getKey()), e.getValue());
		}
		return result;
	}

	@Nullable
	protected TestCaseIdEntry getTestCaseId(@Nullable String codeRef, @Nullable final Map<String, String> params) {
		return TestCaseIdUtils.getTestCaseId(codeRef, ofNullable(params).map(p -> new ArrayList<>(p.values())).orElse(null));
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param step      JBehave step name
	 * @param codeRef   a step code reference
	 * @param startTime a step start time which will be passed to RP
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildStartStepRq(@Nonnull final String step, @Nonnull final String codeRef,
			@Nullable final Map<String, String> params, @Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(formatExampleStep(step, params));
		rq.setCodeRef(codeRef);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(ItemType.STEP.name());
		Map<String, String> usedParams = ofNullable(params).map(p -> getUsedParameters(step).stream()
				.collect(Collectors.toMap(s -> s, p::get))).orElse(null);
		rq.setParameters(getStepParameters(usedParams));
		rq.setTestCaseId(ofNullable(getTestCaseId(codeRef, usedParams)).map(TestCaseIdEntry::getId).orElse(null));
		return rq;
	}

	/**
	 * Extension point to customize lifecycle suite (before/after) creation event/request
	 *
	 * @param name      a lifecycle suite name
	 * @param startTime item start time
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildLifecycleSuiteStartRq(@Nonnull final String name, @Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(name);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(ItemType.TEST.name());
		return rq;
	}

	/**
	 * Extension point to customize lifecycle method (before/after) creation event/request
	 *
	 * @param type      item type
	 * @param name      a lifecycle method name
	 * @param startTime item start time
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildLifecycleMethodStartRq(@Nonnull final ItemType type, @Nonnull final String name,
			@Nullable final Date startTime) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(name);
		rq.setCodeRef(name);
		rq.setStartTime(ofNullable(startTime).orElseGet(() -> Calendar.getInstance().getTime()));
		rq.setType(type.name());
		return rq;
	}

	@Nonnull
	protected Maybe<String> startTestItem(@Nullable final Maybe<String> parentId, @Nonnull final StartTestItemRQ rq) {
		Launch myLaunch = launch.get();
		return ofNullable(parentId).map(p -> myLaunch.startTestItem(p, rq)).orElseGet(() -> myLaunch.startTestItem(rq));
	}

	protected TestItemTree.TestItemLeaf createLeaf(@Nonnull final ItemType type, @Nonnull final StartTestItemRQ rq,
			@Nullable final TestItemTree.TestItemLeaf parent) {
		Optional<TestItemTree.TestItemLeaf> parentOptional = ofNullable(parent);
		Optional<Maybe<String>> parentId = parentOptional.map(TestItemTree.TestItemLeaf::getItemId);
		Maybe<String> itemId = startTestItem(parentId.orElse(null), rq);
		TestItemTree.TestItemLeaf l = parentId.map(p -> TestItemTree.createTestItemLeaf(p, itemId))
				.orElseGet(() -> TestItemTree.createTestItemLeaf(itemId));
		l.setType(type);
		l.setAttribute(START_TIME, rq.getStartTime());
		l.setAttribute(START_REQUEST, rq);
		parentOptional.ifPresent(p -> l.setAttribute(PARENT, p));
		ofNullable(rq.getCodeRef()).ifPresent(r -> l.setAttribute(CODE_REF, r));
		return l;
	}

	@Nonnull
	protected Date getItemDate(@Nullable final TestItemTree.TestItemLeaf parent) {
		final Date previousDate = ofNullable(parent).map(p -> p.<Date>getAttribute(START_TIME))
				.orElseGet(() -> Calendar.getInstance().getTime());
		Date currentDate = Calendar.getInstance().getTime();
		Date itemDate;
		if (previousDate.compareTo(currentDate) <= 0) {
			itemDate = currentDate;
		} else {
			itemDate = previousDate;
		}
		return itemDate;
	}

	@SuppressWarnings("unchecked")
	@Nullable
	protected TestItemTree.TestItemLeaf retrieveLeaf() {
		final List<Pair<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf>> leafChain = new ArrayList<>();
		for (Entity<?> entity : structure) {
			final ItemType itemType = entity.type();
			final Optional<Pair<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf>> parent = leafChain.isEmpty() ?
					Optional.empty() :
					Optional.of(leafChain.get(leafChain.size() - 1));
			final TestItemTree.TestItemLeaf parentLeaf = parent.map(Pair::getValue).orElse(null);
			final Map<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf> children = parent.map(p -> p.getValue().getChildItems())
					.orElseGet(itemTree::getTestItems);
			final String parentCodeRef = parent.map(p -> (String) p.getValue().getAttribute(CODE_REF)).orElse(null);
			Date itemDate = getItemDate(parent.map(Pair::getValue).orElse(null));
			switch (itemType) {
				case STORY:
					Story story = (Story) entity.get();
					TestItemTree.ItemTreeKey storyKey = ItemTreeUtils.createKey(story);
					leafChain.add(ImmutablePair.of(storyKey, children.computeIfAbsent(storyKey, k -> createLeaf(ItemType.STORY,
							buildStartStoryRq(story, getCodeRef(parentCodeRef, k, ItemType.STORY), itemDate),
							parentLeaf
					))));
					break;
				case SCENARIO:
					Scenario scenario = (Scenario) entity.get();
					TestItemTree.ItemTreeKey scenarioKey = ItemTreeUtils.createKey(getScenarioName(scenario));
					leafChain.add(ImmutablePair.of(scenarioKey, children.computeIfAbsent(scenarioKey, k -> createLeaf(ItemType.SCENARIO,
							buildStartScenarioRq(scenario, getCodeRef(parentCodeRef, k, ItemType.SCENARIO), itemDate),
							parentLeaf
					))));
					break;
				case SUITE: // type SUITE == an Example
					Map<String, String> example = (Map<String, String>) entity.get();
					TestItemTree.ItemTreeKey exampleKey = ItemTreeUtils.createKey(example);
					leafChain.add(ImmutablePair.of(exampleKey, children.computeIfAbsent(exampleKey, k -> {
						TestItemTree.TestItemLeaf leaf = createLeaf(ItemType.SUITE,
								buildStartExampleRq(example, getCodeRef(parentCodeRef, k, ItemType.SUITE), itemDate),
								parentLeaf
						);
						leaf.setAttribute(PARAMETERS, example);
						return leaf;
					})));
					break;
				case TEST: // type TEST == a lifecycle SUITE
					String lifecycleSuiteName = (String) entity.get();
					TestItemTree.ItemTreeKey lifecycleSuiteKey = ItemTreeUtils.createKey(lifecycleSuiteName);
					leafChain.add(ImmutablePair.of(lifecycleSuiteKey, children.computeIfAbsent(lifecycleSuiteKey,
							k -> createLeaf(itemType, buildLifecycleSuiteStartRq(lifecycleSuiteName, itemDate), parentLeaf)
					)));
					break;
			}
		}
		return leafChain.get(leafChain.size() - 1).getValue();
	}

	protected TestItemTree.TestItemLeaf startStep(String name, @Nonnull final TestItemTree.TestItemLeaf parent) {
		TestItemTree.ItemTreeKey key = ItemTreeUtils.createKey(name);
		TestItemTree.TestItemLeaf leaf = createLeaf(ItemType.STEP, buildStartStepRq(name,
				getCodeRef(parent.getAttribute(CODE_REF), key, ItemType.STEP),
				parent.getAttribute(PARAMETERS),
				getItemDate(parent)
		), parent);
		parent.getChildItems().put(key, leaf);
		return leaf;
	}

	protected TestItemTree.TestItemLeaf startLifecycleMethod(String name, ItemType itemType,
			@Nonnull final TestItemTree.TestItemLeaf parent) {
		TestItemTree.ItemTreeKey key = ItemTreeUtils.createKey(name);
		TestItemTree.TestItemLeaf leaf = createLeaf(itemType, buildLifecycleMethodStartRq(itemType, name, getItemDate(parent)), parent);
		parent.getChildItems().put(key, leaf);
		return leaf;
	}

	@SuppressWarnings("unchecked")
	@Nullable
	protected TestItemTree.TestItemLeaf getLeaf() {
		final List<Pair<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf>> leafChain = new ArrayList<>();
		for (Entity<?> entity : structure) {
			final ItemType itemType = entity.type();
			final Pair<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf> parent = leafChain.isEmpty() ?
					null :
					leafChain.get(leafChain.size() - 1);
			if (parent != null) {
				if (parent.getValue() == null) {
					return null;
				}
			}
			final Map<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf> children = ofNullable(parent).map(p -> p.getValue()
					.getChildItems()).orElseGet(itemTree::getTestItems);
			switch (itemType) {
				case STORY:
					TestItemTree.ItemTreeKey storyKey = ItemTreeUtils.createKey((Story) entity.get());
					leafChain.add(ImmutablePair.of(storyKey, children.get(storyKey)));
					break;
				case SCENARIO:
					TestItemTree.ItemTreeKey scenarioKey = ItemTreeUtils.createKey(getScenarioName((Scenario) entity.get()));
					leafChain.add(ImmutablePair.of(scenarioKey, children.get(scenarioKey)));
					break;
				case SUITE: // type SUITE == an Example
					TestItemTree.ItemTreeKey exampleKey = ItemTreeUtils.createKey((Map<String, String>) entity.get());
					leafChain.add(ImmutablePair.of(exampleKey, children.get(exampleKey)));
					break;
				default:
					TestItemTree.ItemTreeKey stepKey = ItemTreeUtils.createKey((String) entity.get());
					leafChain.add(ImmutablePair.of(stepKey, children.get(stepKey)));
					break;
			}
		}
		return leafChain.isEmpty() ? null : leafChain.get(leafChain.size() - 1).getValue();
	}

	/**
	 * Finishes an item in the structure
	 *
	 * @param item   an item to finish
	 * @param status a status to set on finish
	 */
	protected void finishItem(@Nullable final TestItemTree.TestItemLeaf item, @Nullable final ItemStatus status) {
		ofNullable(item).ifPresent(i -> {
			FinishTestItemRQ rq = new FinishTestItemRQ();
			rq.setEndTime(Calendar.getInstance().getTime());
			rq.setStatus(ofNullable(status).map(Enum::name).orElse(null));
			Maybe<OperationCompletionRS> response = launch.get().finishTestItem(i.getItemId(), rq);
			i.setStatus(status);
			i.setFinishResponse(response);
			i.setAttribute(FINISH_REQUEST, rq);
		});
	}

	/**
	 * Finishes the last item in the structure
	 *
	 * @param status a status to set on finish
	 */
	protected void finishLastItem(@Nullable final ItemStatus status) {
		TestItemTree.TestItemLeaf item = getLeaf();
		finishItem(item, status);
		structure.pollLast();
	}

	/**
	 * Calculate an Item status according to its child item status and current status. E.G.: SUITE-TEST or TEST-STEP.
	 * <p>
	 * Example 1:
	 * - Current status: {@link ItemStatus#FAILED}
	 * - Child item status: {@link ItemStatus#SKIPPED}
	 * Result: {@link ItemStatus#FAILED}
	 * <p>
	 * Example 2:
	 * - Current status: {@link ItemStatus#PASSED}
	 * - Child item status: {@link ItemStatus#SKIPPED}
	 * Result: {@link ItemStatus#PASSED}
	 * <p>
	 * Example 3:
	 * - Current status: {@link ItemStatus#PASSED}
	 * - Child item status: {@link ItemStatus#FAILED}
	 * Result: {@link ItemStatus#FAILED}
	 * <p>
	 * Example 4:
	 * - Current status: {@link ItemStatus#SKIPPED}
	 * - Child item status: {@link ItemStatus#FAILED}
	 * Result: {@link ItemStatus#FAILED}
	 *
	 * @param currentStatus an Item status
	 * @param childStatus   a status of its child element
	 * @return new status
	 */
	@Nullable
	protected ItemStatus evaluateStatus(@Nullable final ItemStatus currentStatus, @Nullable final ItemStatus childStatus) {
		return StatusEvaluation.evaluateStatus(currentStatus, childStatus);
	}

	protected void evaluateAndFinishLastItem() {
		TestItemTree.TestItemLeaf item = getLeaf();
		ofNullable(item).ifPresent(i -> {
			ItemStatus status = i.getStatus();
			for (Map.Entry<TestItemTree.ItemTreeKey, TestItemTree.TestItemLeaf> entry : i.getChildItems().entrySet()) {
				TestItemTree.TestItemLeaf value = entry.getValue();
				if (value != null) {
					status = evaluateStatus(status, value.getStatus());
				}
			}
			i.setStatus(status);
			finishItem(i, status);
		});
		structure.pollLast();
	}

	/**
	 * Prepare a function which creates a {@link SaveLogRQ} from a {@link Throwable}
	 *
	 * @param level   a log level
	 * @param message a message to attach
	 * @return a {@link SaveLogRQ} supplier {@link Function}
	 */
	@Nonnull
	protected Function<String, SaveLogRQ> getLogSupplier(@Nonnull final LogLevel level, @Nullable final String message) {
		return itemUuid -> {
			SaveLogRQ rq = new SaveLogRQ();
			rq.setItemUuid(itemUuid);
			rq.setLevel(level.name());
			rq.setLogTime(Calendar.getInstance().getTime());
			rq.setMessage(message);
			rq.setLogTime(Calendar.getInstance().getTime());
			return rq;
		};
	}

	/**
	 * Send a message to report portal about appeared failure
	 *
	 * @param thrown {@link Throwable} object with details of the failure
	 */
	protected void sendStackTraceToRP(@Nonnull Maybe<String> itemId, @Nullable final Throwable thrown) {
		ofNullable(thrown).ifPresent(t -> ReportPortal.emitLog(itemId, getLogSupplier(LogLevel.ERROR, ExceptionUtils.getStackTrace(t))));
	}

	protected void finishItem(final @Nonnull Maybe<String> id, final @Nonnull ItemStatus status, @Nullable Issue issue) {
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		rq.setStatus(status.name());
		rq.setIssue(issue);
		launch.get().finishTestItem(id, rq);
	}

	protected void finishStep(final @Nonnull TestItemTree.TestItemLeaf step, final @Nonnull ItemStatus status, @Nullable Issue issue) {
		finishItem(step.getItemId(), status, issue);
		step.setStatus(status);
	}

	protected void finishStep(final @Nonnull TestItemTree.TestItemLeaf step, final @Nonnull ItemStatus status) {
		finishStep(step, status, null);
	}

	protected void simulateStep(String step) {
		TestItemTree.TestItemLeaf item = retrieveLeaf();
		ofNullable(item).ifPresent(i -> {
			TestItemTree.TestItemLeaf leaf = startStep(step, i);
			finishStep(leaf, ItemStatus.SKIPPED);
		});
	}

	/**
	 * Starts story (test suite level) in ReportPortal
	 *
	 * @param story      JBehave story object
	 * @param givenStory whether or not this story is given
	 * @see <a href="https://jbehave.org/reference/latest/given-stories.html">Given Stories</a>
	 */
	@Override
	public void beforeStory(Story story, boolean givenStory) {
		currentLifecycleItemType = AFTER_STORIES.equals(story.getName()) ? ItemType.AFTER_SUITE : ItemType.BEFORE_SUITE;
		structure.add(new Entity<>(ItemType.STORY, story));
	}

	/**
	 * Finishes story in ReportPortal
	 */
	@Override
	public void afterStory(boolean givenStory) {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && previousItem.getType() == ItemType.TEST) {
			evaluateAndFinishLastItem();
		}
		evaluateAndFinishLastItem();
	}

	@Override
	public void storyCancelled(Story story, StoryDuration storyDuration) {
		finishLastItem(ItemStatus.SKIPPED);
	}

	/**
	 * Starts scenario in ReportPortal (test level)
	 *
	 * @param scenario JBehave scenario object
	 */
	@Override
	public void beforeScenario(Scenario scenario) {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && previousItem.getType() == ItemType.TEST) {
			evaluateAndFinishLastItem();
		}
		currentLifecycleItemType = ItemType.BEFORE_TEST;
		structure.add(new Entity<>(ItemType.SCENARIO, scenario));
	}

	/**
	 * Finishes scenario in ReportPortal
	 */
	@Override
	public void afterScenario() {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && previousItem.getType() == ItemType.TEST) {
			evaluateAndFinishLastItem();
		}
		currentLifecycleItemType = ItemType.AFTER_SUITE;
		evaluateAndFinishLastItem();
	}

	/**
	 * Starts step in ReportPortal (TestStep level)
	 *
	 * @param step Step to be reported
	 */
	@Override
	public void beforeStep(String step) {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && previousItem.getType() == ItemType.TEST) {
			evaluateAndFinishLastItem();
		}
		currentLifecycleItemType = ItemType.BEFORE_METHOD;
		lastStep = currentStep.computeIfAbsent(step, s -> ofNullable(retrieveLeaf()).map(l -> startStep(step, l)).orElse(null));
	}

	@Override
	public void beforeExamples(List<String> steps, ExamplesTable table) {
		// do nothing we will handle it on 'example' method call
	}

	@Override
	public void example(Map<String, String> tableRow, int exampleIndex) {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && (previousItem.getType() == ItemType.TEST || previousItem.getType() == ItemType.SUITE)) {
			evaluateAndFinishLastItem();
		}
		structure.add(new Entity<>(ItemType.SUITE, tableRow)); // type SUITE is used for Examples
	}

	@Override
	public void afterExamples() {
		TestItemTree.TestItemLeaf previousItem = getLeaf();
		if (previousItem != null && previousItem.getType() == ItemType.TEST) {
			evaluateAndFinishLastItem();
		}
		evaluateAndFinishLastItem();
	}

	/**
	 * Finishes step in ReportPortal
	 */
	@Override
	public void successful(String step) {
		launch.get().getStepReporter().finishPreviousStep();
		currentLifecycleItemType = ItemType.AFTER_TEST;
		ofNullable(currentStep.remove(step)).ifPresent(s -> finishStep(s, ItemStatus.PASSED));
	}

	@Override
	public void failed(String step, Throwable cause) {
		launch.get().getStepReporter().finishPreviousStep();
		TestItemTree.TestItemLeaf item = retrieveLeaf();
		boolean isLifecycleMethod = item == null || !currentStep.containsKey(step);
		if (isLifecycleMethod) {
			if (item == null) {
				// failed @BeforeStories (annotated) methods
				structure.add(new Entity<>(ItemType.TEST, currentLifecycleItemType == null ? BEFORE_STORIES : AFTER_STORIES));
			} else if (item.getType() == ItemType.STORY) {
				// failed @BeforeStory, @BeforeScenario (annotated) methods
				structure.add(new Entity<>(ItemType.TEST, currentLifecycleItemType == ItemType.BEFORE_SUITE ? BEFORE_STORY : AFTER_STORY));
			}
			currentStep.computeIfAbsent(
					step,
					s -> ofNullable(retrieveLeaf()).map(i -> startLifecycleMethod(s, currentLifecycleItemType, i)).orElse(null)
			);
		}
		ofNullable(currentStep.remove(step)).ifPresent(i -> {
			sendStackTraceToRP(i.getItemId(), cause);
			finishStep(i, ItemStatus.FAILED);
		});
	}

	@Override
	public void ignorable(String step) {
		ofNullable(retrieveLeaf()).ifPresent(l -> {
			TestItemTree.TestItemLeaf leaf = startStep(step, l);
			finishStep(leaf, ItemStatus.SKIPPED);
		});
	}

	@Override
	public void notPerformed(String step) {
		TestItemTree.TestItemLeaf item = retrieveLeaf();
		ofNullable(item).ifPresent(i -> {
			TestItemTree.TestItemLeaf leaf = startStep(step, i);
			ReportPortal.emitLog(leaf.getItemId(),
					getLogSupplier(LogLevel.WARN, "Step execution was skipped by JBehave, see previous steps for errors.")
			);
			finishStep(leaf, ItemStatus.SKIPPED, Launch.NOT_ISSUE);
		});
	}

	@Override
	public void pending(String step) {
		TestItemTree.TestItemLeaf item = retrieveLeaf();
		ofNullable(item).ifPresent(i -> {
			TestItemTree.TestItemLeaf leaf = startStep(step, i);
			ReportPortal.emitLog(leaf.getItemId(),
					getLogSupplier(LogLevel.WARN, String.format("Unable to locate a step implementation: '%s'", step))
			);
			finishStep(leaf, ItemStatus.SKIPPED);
		});
	}

	@Override
	public void scenarioNotAllowed(Scenario scenario, String filter) {
		if (null != scenario.getExamplesTable() && scenario.getExamplesTable().getRowCount() > 0) {
			beforeExamples(scenario.getSteps(), scenario.getExamplesTable());
			for (int i = 0; i < scenario.getExamplesTable().getRowCount(); i++) {
				example(scenario.getExamplesTable().getRow(i));
				for (String step : scenario.getSteps()) {
					simulateStep(step);
				}
			}
		} else {
			for (String step : scenario.getSteps()) {
				simulateStep(step);
			}
		}
		finishLastItem(ItemStatus.SKIPPED);
	}

	protected static class Entity<T> {

		private final ItemType type;
		private final T value;

		public Entity(ItemType itemType, T itemValue) {
			type = itemType;
			value = itemValue;
		}

		public ItemType type() {
			return type;
		}

		public T get() {
			return value;
		}
	}
}
