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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.resources.Directory;
import org.sonar.api.resources.DuplicatedSourceException;
import org.sonar.api.resources.Project;
import org.sonar.api.rules.RuleFinder;
import org.sonar.plugins.delphi.antlr.analyzer.ASTAnalyzer;
import org.sonar.plugins.delphi.antlr.analyzer.CodeAnalysisCacheResults;
import org.sonar.plugins.delphi.antlr.analyzer.DelphiASTAnalyzer;
import org.sonar.plugins.delphi.antlr.ast.ASTTree;
import org.sonar.plugins.delphi.antlr.ast.DelphiAST;
import org.sonar.plugins.delphi.antlr.sanitizer.DelphiSourceSanitizer;
import org.sonar.plugins.delphi.core.helpers.DelphiProjectHelper;
import org.sonar.plugins.delphi.core.language.ClassInterface;
import org.sonar.plugins.delphi.core.language.FunctionInterface;
import org.sonar.plugins.delphi.core.language.UnitInterface;
import org.sonar.plugins.delphi.metrics.BasicMetrics;
import org.sonar.plugins.delphi.metrics.ComplexityMetrics;
import org.sonar.plugins.delphi.metrics.DeadCodeMetrics;
import org.sonar.plugins.delphi.metrics.LCOM4Metrics;
import org.sonar.plugins.delphi.metrics.MetricsInterface;
import org.sonar.plugins.delphi.project.DelphiProject;
import org.sonar.plugins.delphi.utils.DelphiUtils;
import org.sonar.plugins.delphi.utils.ProgressReporter;
import org.sonar.plugins.delphi.utils.ProgressReporterLogger;

/**
 * Main DelphiLanguage sensor class, it executes on DelphiLanguage project and
 * gathers all data through metrics.
 */
public class DelphiSensor implements Sensor {

    private int scannedFiles = 0; // number of scanned files
    private Project project = null; // project
    private Set<Directory> packageList = new HashSet<Directory>(); // package
                                                                   // list
    private Map<Directory, Integer> filesCount = new HashMap<Directory, Integer>();
    // list of resources to process for metrics
    private List<InputFile> resourceList = new ArrayList<InputFile>();
    private Map<InputFile, List<ClassInterface>> fileClasses = new HashMap<InputFile, List<ClassInterface>>();
    private Map<InputFile, List<FunctionInterface>> fileFunctions = new HashMap<InputFile, List<FunctionInterface>>();
    private List<UnitInterface> units = null; // project units
    private List<File> testDirectories = null; // test directories

    private final DelphiProjectHelper delphiProjectHelper;
    private final RuleFinder ruleFinder;
    private final ResourcePerspectives perspectives;

    public DelphiSensor(DelphiProjectHelper delphiProjectHelper, RuleFinder ruleFinder,
            ResourcePerspectives perspectives) {
        this.delphiProjectHelper = delphiProjectHelper;
        this.ruleFinder = ruleFinder;
        this.perspectives = perspectives;
    }

    /**
     * Determines if sensor should execute on project
     * 
     * @return true if we are analysing DelphiLanguage project, false otherwise
     */

    public boolean shouldExecuteOnProject(Project project) {
        return delphiProjectHelper.shouldExecuteOnProject();
    }

    /**
     * Analyses whole project with all metrics
     */

    public void analyse(Project sonarProject, SensorContext sensorContext) {
        project = sonarProject; // project to analyse
        testDirectories = delphiProjectHelper.getTestDirectories(project);
        printFileList("Source dir: ", project.getFileSystem().getSourceDirs());
        printFileList("Test dir: ", testDirectories);

        // creates and resets analyser
        ASTAnalyzer analyzer = new DelphiASTAnalyzer(delphiProjectHelper);
        List<DelphiProject> projects = delphiProjectHelper.getWorkgroupProjects();
        for (DelphiProject delphiProject : projects) // for every .dproj file
        {
            CodeAnalysisCacheResults.resetCache();
            parseFiles(analyzer, delphiProject);
            parsePackages(sensorContext);

            MetricsInterface metrics[] = {new BasicMetrics(project), new ComplexityMetrics(project),
                    new LCOM4Metrics(project),
                    new DeadCodeMetrics(project, ruleFinder, perspectives)};
            processFiles(metrics, sensorContext);
        }
    }

    /**
     * Calculate metrics for project files
     * 
     * @param metrics Metrics to calculate
     * @param sensorContext Sensor context (provided by Sonar)
     */
    private void processFiles(MetricsInterface[] metrics, SensorContext sensorContext) {
        DelphiUtils.LOG.info("Processing metrics...");
        ProgressReporter progressReporter = new ProgressReporter(resourceList.size(), 10, new ProgressReporterLogger(
                DelphiUtils.LOG));

        for (InputFile resource : resourceList) { // for every resource
            DelphiUtils.LOG.debug(">> PROCESSING " + resource.file().getPath());
            for (MetricsInterface metric : metrics) { // for every metric
                if (metric.executeOnResource(resource)) {
                    metric.analyse(resource, sensorContext, fileClasses.get(resource), fileFunctions.get(resource),
                            units);
                    InputFile inputFile = delphiProjectHelper.getFile(resource.file().getAbsolutePath());
                    metric.save(inputFile, sensorContext);
                }
            } // metric

            // calculating undocumented api
            double udApi = DelphiUtils.checkRange(
                    metrics[1].getMetric("PUBLIC_API") - metrics[0].getMetric("PUBLIC_DOC_API"), 0.0,
                    Double.MAX_VALUE);

            // Number of public API without a Javadoc block
            sensorContext.saveMeasure(resource, CoreMetrics.PUBLIC_UNDOCUMENTED_API, udApi);

            progressReporter.progress();
        }

        DelphiUtils.LOG.info("Done");
    }

    /**
     * Count the metrics for packages
     * 
     * @param sensorContext Sensor context (provided by Sonar)
     */
    private void parsePackages(SensorContext sensorContext) {
        // for every package
        for (Directory pack : packageList) {
            sensorContext.saveMeasure(pack, CoreMetrics.DIRECTORIES, 1.0);
            sensorContext.saveMeasure(pack, CoreMetrics.FILES, (double) filesCount.get(pack));
        }
    }

    // for debugging, prints file paths with message to debug file
    private void printFileList(String msg, List<File> list) {
        for (File f : list) {
            DelphiUtils.LOG.info(msg + f.getAbsolutePath());
        }
    }

    /**
     * Parse files with ANTLR
     * 
     * @param analyser Analyser to use
     * @param delphiProject DelphiLanguage project to parse
     */
    protected void parseFiles(ASTAnalyzer analyser, DelphiProject delphiProject) {
        // project properties
        List<File> includedDirs = delphiProject.getIncludeDirectories();
        List<File> excludedDirs = delphiProjectHelper.getExcludedSources();
        List<File> sourceFiles = delphiProject.getSourceFiles();
        List<String> definitions = delphiProject.getDefinitions();
        boolean importSources = delphiProjectHelper.getImportSources();

        DelphiSourceSanitizer.setIncludeDirectories(includedDirs);
        DelphiSourceSanitizer.setDefinitions(definitions);

        printFileList("Included: ", includedDirs);
        printFileList("Excluded: ", excludedDirs);

        // for every source file
        DelphiUtils.LOG.info("Parsing project " + delphiProject.getName());

        ProgressReporter progressReporter = new ProgressReporter(sourceFiles.size(), 10, new ProgressReporterLogger(
                DelphiUtils.LOG));
        DelphiUtils.LOG.info("Files to parse: " + sourceFiles.size());

        for (File delphiFile : sourceFiles) {
            parseSourceFile(delphiFile, excludedDirs, importSources, analyser);
            progressReporter.progress();
        }

        units = analyser.getResults().getCachedUnitsAsList();
        DelphiUtils.LOG.info("Done");
    }

    /**
     * Parses a source file
     * 
     * @param sourceFile Source file to parse
     * @param excludedDirs List of excluded dirs
     * @param importSources Should we import sources to Sonar
     * @param analyzer Source code analyser
     */
    private void parseSourceFile(File sourceFile, List<File> excludedDirs, boolean importSources, ASTAnalyzer analyzer) {
        if (delphiProjectHelper.isExcluded(sourceFile, excludedDirs)) {
            return; // in excluded, return
        }

        boolean isTest = delphiProjectHelper.isTestFile(sourceFile, testDirectories);

        DelphiUtils.LOG.debug(">> PARSING " + sourceFile.getAbsolutePath() + " test: " + isTest + " directory: "
                + sourceFile.getParentFile());

        // adding file to package
        InputFile resource = delphiProjectHelper.getFile(sourceFile);
        // DelphiFile resource = DelphiFile.fromIOFile(sourceFile,
        // project.getFileSystem().getSourceDirs(), isTest);

        Directory pack = delphiProjectHelper.getDirectory(sourceFile.getParentFile(), project);

        if (pack == null) {
            throw new IllegalArgumentException("Directory: " + sourceFile.getParentFile() + " not found.");
            // DelphiUtils.LOG.debug("Directory: " + sourceFile.getParentFile()
            // + " not found.");
            // return;
        }

        packageList.add(pack); // new pack

        if (filesCount.containsKey(pack)) {
            filesCount.put(pack, filesCount.get(pack) + 1); // files count
        } else {
            filesCount.put(pack, Integer.valueOf(1));
        }
        resourceList.add(resource);

        // sonarIndex.index(resource);
        ASTTree ast = analyseSourceFile(sourceFile, analyzer);
        if (importSources && ast != null) {

            try {
                ast.getFileSource();
            } catch (DuplicatedSourceException e) {
                DelphiUtils.LOG.debug("Source already saved, skipping...");
            }
        }

        fileClasses.put(resource, analyzer.getResults().getClasses());
        fileFunctions.put(resource, analyzer.getResults().getFunctions());
    }

    /**
     * Analysing a source file with ANTLR
     * 
     * @param sourceFile File to analyse
     * @param analyser Source code analyser
     * @return AST Tree
     */
    private ASTTree analyseSourceFile(File sourceFile, ASTAnalyzer analyser) {
        // analysing file
        DelphiAST ast = null;
        try {
            ast = new DelphiAST(sourceFile); // ast tree for file
            analyser.analyze(ast); // parsing with ANTLR
            ++scannedFiles;
        } catch (Exception e) {
            DelphiUtils.LOG.debug("Error parsing file: " + e.getMessage() + " " + sourceFile.getAbsolutePath());
        }
        return ast;
    }

    /**
     * Get the number of processed files
     * 
     * @return The number of processed files
     */
    public int getProcessedFilesCount() {
        return scannedFiles;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
