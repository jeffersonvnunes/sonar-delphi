/*
 * Sonar Delphi Plugin
 * Copyright (C) 2011 Sabre Airline Solutions and Fabricio Colombo
 * Author(s):
 * Przemyslaw Kociolek (przemyslaw.kociolek@sabre.com)
 * Michal Wojcik (michal.wojcik@sabre.com)
 * Fabricio Colombo (fabricio.colombo.mva@gmail.com)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.delphi;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.measures.Measure;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.ProjectFileSystem;
import org.sonar.api.rules.RuleFinder;
import org.sonar.plugins.delphi.core.helpers.DelphiProjectHelper;
import org.sonar.plugins.delphi.debug.DebugSensorContext;
import org.sonar.plugins.delphi.debug.ProjectMetricsXMLParser;
import org.sonar.plugins.delphi.project.DelphiProject;
import org.sonar.plugins.delphi.utils.DelphiUtils;

public class DelphiSensorTest {

    private Project project = null;
    private DelphiSensor sensor = null;
    private File baseDir = null;
    private Map<String, Integer> keyMetricIndex = null;
    private DelphiProjectHelper delphiProjectHelper;
    private RuleFinder ruleFinder;
    private ResourcePerspectives perspectives;

    private static final String ROOT_NAME = "/org/sonar/plugins/delphi/SimpleDelphiProject";

    @Before
    public void init() {

        project = mock(Project.class);

        ProjectFileSystem pfs = mock(ProjectFileSystem.class);

        baseDir = DelphiUtils.getResource(ROOT_NAME);
        File reportDir = new File(baseDir.getAbsolutePath() + "/reports");

        File[] dirs = baseDir.listFiles(DelphiUtils.getDirectoryFilter()); // get
                                                                           // all
                                                                           // directories

        List<File> sourceDirs = new ArrayList<File>(dirs.length);
        List<InputFile> sourceFiles = new ArrayList<InputFile>();

        sourceDirs.add(baseDir); // include baseDir
        for (File source : baseDir.listFiles(DelphiUtils.getFileFilter())) {
            sourceFiles.add(new DefaultInputFile(ROOT_NAME).setFile(source));
        }

        for (File directory : dirs) { // get all source files from all
                                      // directories
            File[] files = directory.listFiles(DelphiUtils.getFileFilter());
            for (File sourceFile : files) {
                sourceFiles.add(new DefaultInputFile(ROOT_NAME).setFile(sourceFile));
            }
            sourceDirs.add(directory); // put all directories to list
        }

        when(project.getFileSystem()).thenReturn(pfs);

        when(pfs.getBasedir()).thenReturn(baseDir);
        when(pfs.getSourceDirs()).thenReturn(sourceDirs);
        when(pfs.getReportOutputDir()).thenReturn(reportDir);

        perspectives = mock(ResourcePerspectives.class);

        delphiProjectHelper = mock(DelphiProjectHelper.class);
        when(delphiProjectHelper.shouldExecuteOnProject()).thenReturn(Boolean.TRUE);

        DelphiProject delphiProject = new DelphiProject("Default Project");
        delphiProject.setSourceFiles(sourceFiles);

        when(delphiProjectHelper.getWorkgroupProjects()).thenReturn(Arrays.asList(delphiProject));
        when(delphiProjectHelper.getDirectory(any(File.class), any(Project.class))).thenCallRealMethod();
        when(delphiProjectHelper.getFile(any(File.class))).thenAnswer(new Answer<InputFile>() {

            @Override
            public InputFile answer(InvocationOnMock invocation) throws Throwable {
                InputFile inputFile = new DefaultInputFile(ROOT_NAME).setFile((File) invocation.getArguments()[0]);

                return inputFile;
            }
        });
        when(delphiProjectHelper.getFile(any(String.class))).thenAnswer(new Answer<InputFile>() {

            @Override
            public InputFile answer(InvocationOnMock invocation) throws Throwable {
                InputFile inputFile = new DefaultInputFile(ROOT_NAME).setFile(new File((String) invocation
                        .getArguments()[0]));

                return inputFile;
            }
        });

        ruleFinder = mock(RuleFinder.class);

        sensor = new DelphiSensor(delphiProjectHelper, ruleFinder, perspectives);
    }

    @Test
    public void shouldExecuteOnProject() {
        assertTrue(sensor.shouldExecuteOnProject(project));
    }

    @Test
    public void analyseTest() {
        createKeyMetricIndexMap();

        ProjectMetricsXMLParser xmlParser = new ProjectMetricsXMLParser(new File(baseDir.getAbsolutePath()
                + File.separator + "values.xml")); // xml file for
        // expected
        // metrics for
        // files
        DebugSensorContext context = new DebugSensorContext(); // new debug
                                                               // context for
                                                               // debug
                                                               // information
        sensor.analyse(project, context); // analysing project

        Map<String, Double[]> expectedValues = new HashMap<String, Double[]>(); // create
                                                                                // a
                                                                                // map
                                                                                // of
                                                                                // expected
                                                                                // values
                                                                                // for
                                                                                // each
                                                                                // file
        for (String fileName : xmlParser.getFileNames()) {
            expectedValues.put(fileName, xmlParser.getFileValues(fileName));
        }

        for (String key : context.getMeasuresKeys()) { // check each measure if
                                                       // it is correct
            String fileKey = key.substring(0, key.lastIndexOf(':')); // get file
                                                                     // name
            String metricKey = key.substring(key.lastIndexOf(':') + 1, key.length()); // get
                                                                                      // metric
                                                                                      // key

            if (!expectedValues.containsKey(fileKey)) {
                continue; // skip [default] package
            }
            if (keyMetricIndex.get(metricKey) == null) {
                continue;
            }

            Measure<?> measure = context.getMeasure(key);
            double currentValue = measure.getValue();
            double expectedValue = expectedValues.get(fileKey)[keyMetricIndex.get(metricKey)];

            assertEquals(fileKey + "@" + metricKey, expectedValue, currentValue, 0.0);
        }
    }

    private void createKeyMetricIndexMap() {
        keyMetricIndex = new HashMap<String, Integer>();
        keyMetricIndex.put("lcom4", 0);
        keyMetricIndex.put("complexity", 1);
        keyMetricIndex.put("functions", 2);
        keyMetricIndex.put("function_complexity", 3);
        keyMetricIndex.put("classes", 4);
        keyMetricIndex.put("lines", 5);
        keyMetricIndex.put("comment_lines", 6);
        keyMetricIndex.put("accessors", 7);
        keyMetricIndex.put("public_undocumented_api", 8);
        keyMetricIndex.put("ncloc", 9);
        keyMetricIndex.put("files", 10);
        keyMetricIndex.put("package.files", 11);
        keyMetricIndex.put("package.packages", 12);
        keyMetricIndex.put("class_complexity", 13);
        keyMetricIndex.put("noc", 14);
        keyMetricIndex.put("statements", 15);
        keyMetricIndex.put("rfc", 16);
        keyMetricIndex.put("dit", 17);
        keyMetricIndex.put("public_api", 18);
        keyMetricIndex.put("comment_blank_lines", 19);
    }

    @Test
    public void analyseFileOnRootDir() {
        createKeyMetricIndexMap();

        ProjectMetricsXMLParser xmlParser = new ProjectMetricsXMLParser(new File(baseDir.getAbsolutePath()
                + File.separator + "values.xml")); // xml file for
        // expected
        // metrics for
        // files
        DebugSensorContext context = new DebugSensorContext(); // new debug
                                                               // context for
                                                               // debug
                                                               // information
        sensor.analyse(project, context); // analysing project

        Map<String, Double[]> expectedValues = new HashMap<String, Double[]>(); // create
                                                                                // a
                                                                                // map
                                                                                // of
                                                                                // expected
                                                                                // values
                                                                                // for
                                                                                // each
                                                                                // file
        for (String fileName : xmlParser.getFileNames()) {
            expectedValues.put(fileName, xmlParser.getFileValues(fileName));
        }

        for (String key : context.getMeasuresKeys()) { // check each measure if
                                                       // it is correct
            String fileKey = key.substring(0, key.lastIndexOf(':')); // get file
                                                                     // name
            String metricKey = key.substring(key.lastIndexOf(':') + 1, key.length()); // get
                                                                                      // metric
                                                                                      // key

            if (!expectedValues.containsKey(fileKey)) {
                continue; // skip [default] package
            }
            if (keyMetricIndex.get(metricKey) == null) {
                continue;
            }

            Measure<?> measure = context.getMeasure(key);
            double currentValue = measure.getValue();
            double expectedValue = expectedValues.get(fileKey)[keyMetricIndex.get(metricKey)];

            assertEquals(fileKey + "@" + metricKey, expectedValue, currentValue, 0.0);
        }
    }

}
