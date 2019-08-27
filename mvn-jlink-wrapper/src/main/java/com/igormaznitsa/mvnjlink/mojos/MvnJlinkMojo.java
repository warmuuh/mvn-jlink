/*
 * Copyright 2019 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.igormaznitsa.mvnjlink.mojos;

import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.meta.common.utils.GetUtils;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.igormaznitsa.mvnjlink.utils.StringUtils.extractJdepsModuleNames;
import static java.nio.file.Files.isDirectory;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.io.FileUtils.deleteDirectory;

/**
 * Execute JLINK tool from provided JDK.
 */
@Mojo(name = "jlink", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class MvnJlinkMojo extends AbstractJdkToolMojo {

  private static final String SPECNAME_JDK_JMODS = "JDK.PROVIDER.JMODS";
  
  /**
   * Path to the report generated by JDEPS.
   */
  @Parameter(name = "jdepsReportPath")
  private String jdepsReportPath;

  /**
   * Command line options for the tool.
   */
  @Parameter(name = "options")
  private List<String> options = new ArrayList<>();

  /**
   * List of paths to module folders. By default it uses jmods folder of provider's JDK,
   * you can use the path among yours through pseudo path name 'JDK.PROVIDER.JMODS' 
   * @since 1.0.4
   */
  @Parameter(name = "modulePaths")
  private String [] modulePaths;

  /**
   * List of modules to be added.
   */
  @Parameter(name = "addModules")
  private List<String> addModules = new ArrayList<>();

  /**
   * Path to place generated image.
   */
  @Parameter(name = "output", required = true)
  private String output;

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  @Nonnull
  @MustNotContainNull
  private static List<String> extractModuleNamesFromJdepsReport(@Nonnull final Optional<Path> jdepsReportPath) throws MojoExecutionException {
    if (jdepsReportPath.isPresent()) {
      final Path jdepsFile = jdepsReportPath.get();

      try {
        return extractJdepsModuleNames(FileUtils.readFileToString(jdepsFile.toFile(), Charset.defaultCharset()));
      } catch (IOException ex) {
        throw new MojoExecutionException("Can't read jdeps out file:" + jdepsFile, ex);
      }
    } else {
      return Collections.emptyList();
    }
  }

  @Nonnull
  @MustNotContainNull
  public List<String> getOptions() {
    return this.options;
  }

  public void setOptions(@Nullable @MustNotContainNull final List<String> value) {
    this.options = GetUtils.ensureNonNull(value, new ArrayList<>());
  }

  @Nonnull
  private Path findJmodsFolderInJdk(@Nonnull final Path jdk) throws MojoExecutionException {
    final Path pathToMods = jdk.resolve("jmods");
    if (!Files.isDirectory(pathToMods)) {
      throw new MojoExecutionException("Can't find jmods folder: " + pathToMods.toString());
    }
    return pathToMods;
  }

  @Nonnull
  private String formModulePath(@Nonnull final Log log, @Nonnull final Path providerJdk) throws MojoExecutionException {
    final String result;
    if (this.modulePaths == null) {
      log.info("Provider JDK will be used as module path source: " + providerJdk);
      result = findJmodsFolderInJdk(providerJdk).toString();
    } else {
      final StringBuilder buffer = new StringBuilder();
      
      String separator = "";
      
      for (final String path : this.modulePaths) {
        buffer.append(separator);
        
        final File modulePathFolder = new File(path.trim().equals(SPECNAME_JDK_JMODS) ? findJmodsFolderInJdk(providerJdk).toString() : path);
        
        log.debug("Adding module path: "+modulePathFolder);
        if (!modulePathFolder.isDirectory()) {
          throw new MojoExecutionException("Can't find folder defined in 'modulePaths': " + modulePathFolder.getAbsolutePath());
        }

        separator = File.pathSeparator;
      }
      result = buffer.toString();
    }
    log.info("Formed module path: " + result);
    return result;
  }
  
  @Override
  public void onExecute() throws MojoExecutionException, MojoFailureException {
    final Log log = this.getLog();

    final Path providerJdk = this.getSourceJdkFolderFromProvider();

    final Path outputPath = Paths.get(this.output);

    final String pathToJlink = this.findJdkTool("jlink");
    if (pathToJlink == null) {
      throw new MojoExecutionException("Can't find jlink in JDK");
    }
    final Path execJlinkPath = Paths.get(pathToJlink);

    final List<String> modulesFromJdeps = extractModuleNamesFromJdepsReport(ofNullable(this.jdepsReportPath == null ? null : Paths.get(this.jdepsReportPath)));
    final List<String> totalModules = new ArrayList<>(modulesFromJdeps);
    totalModules.addAll(this.addModules);

    final String joinedAddModules = totalModules.stream().map(String::trim).collect(joining(","));

    log.info("List of modules : " + joinedAddModules);

    final List<String> commandLineOptions = new ArrayList<>(this.getOptions());

    commandLineOptions.add("--module-path");
    commandLineOptions.add(formModulePath(log, providerJdk));
    
    final int indexOptions = commandLineOptions.indexOf("--add-modules");
    if (indexOptions < 0) {
      if (joinedAddModules.isEmpty()) {
        throw new MojoExecutionException("There are not provided modules to be added.");
      }
      commandLineOptions.add("--add-modules");
      commandLineOptions.add(joinedAddModules);
    } else {
      if (!joinedAddModules.isEmpty()) {
        commandLineOptions.set(indexOptions + 1, commandLineOptions.get(indexOptions + 1) + ',' + joinedAddModules);
      }
    }

    if (isDirectory(outputPath)) {
      log.warn("Deleting existing output folder: " + outputPath);
      try {
        deleteDirectory(outputPath.toFile());
      } catch (IOException ex) {
        throw new MojoExecutionException("Can't delete output folder: " + outputPath, ex);
      }
    }

    final List<String> commandLine = new ArrayList<>();
    commandLine.add(execJlinkPath.toString());
    commandLine.add("--output");
    commandLine.add(outputPath.toString());
    commandLine.addAll(commandLineOptions);

    this.getLog().info("CLI arguments: " + commandLine.stream().skip(1).collect(Collectors.joining(" ")));

    log.debug("Command line: " + commandLine);

    final ByteArrayOutputStream consoleOut = new ByteArrayOutputStream();
    final ByteArrayOutputStream consoleErr = new ByteArrayOutputStream();

    final ProcessResult executor;
    try {
      executor = new ProcessExecutor(commandLine)
          .readOutput(true)
          .redirectOutput(consoleOut)
          .redirectError(consoleErr)
          .exitValueAny()
          .executeNoTimeout();
    } catch (IOException ex) {
      throw new MojoExecutionException("Error during execution", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new MojoFailureException("Execution interrupted", ex);
    }

    if (executor.getExitValue() == 0) {
      log.debug(new String(consoleOut.toByteArray(), Charset.defaultCharset()));
      log.info("Execution completed successfully, the result folder is " + outputPath);
    } else {
      final String textOut = new String(consoleOut.toByteArray(), Charset.defaultCharset());
      final String textErr = new String(consoleErr.toByteArray(), Charset.defaultCharset());

      if (executor.getExitValue() == 1 && textOut.contains("Error: java.lang.IllegalArgumentException")) {
        log.error("It looks like that the current JDK is incompatible with the provided JDK!");
      }

      if (textErr.isEmpty()) {
        log.error(textOut);
      } else {
        log.info(textOut);
        log.error(textErr);
      }

      throw new MojoFailureException("jlink returns error status code: " + executor.getExitValue());
    }
  }
}
