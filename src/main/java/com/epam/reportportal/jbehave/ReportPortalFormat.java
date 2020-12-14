/*
 * Copyright (C) 2019 EPAM Systems
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

import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.utils.MemoizingSupplier;
import org.jbehave.core.reporters.FilePrintStreamFactory;
import org.jbehave.core.reporters.Format;
import org.jbehave.core.reporters.StoryReporter;
import org.jbehave.core.reporters.StoryReporterBuilder;
import rp.com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;

/**
 * ReportPortal format. Adds possibility to report story execution results to
 * ReportPortal. Requires using one of execution decorators to start and finish
 * execution in RP
 *
 * @author Andrei Varabyeu
 */
public class ReportPortalFormat extends Format {

    public static final Format INSTANCE = new ReportPortalFormat();

    private final MemoizingSupplier<StoryReporter> reporter;

    public ReportPortalFormat() {
        this(ReportPortal.builder().build());
    }

    public ReportPortalFormat(ReportPortal rp) {
        super("REPORT_PORTAL");
        reporter = createStoryReporter(rp);
    }

    protected MemoizingSupplier<StoryReporter> createStoryReporter(final ReportPortal rp) {
        return new MemoizingSupplier<>(() -> new ReportPortalStoryReporter(rp));
    }

    @Override
    public StoryReporter createStoryReporter(FilePrintStreamFactory factory, StoryReporterBuilder storyReporterBuilder) {
        return reporter.get();
    }
}
