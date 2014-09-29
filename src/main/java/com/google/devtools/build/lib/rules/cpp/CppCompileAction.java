// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.MiddlemanExpander;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.extra.CppCompileInfo;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.actions.ConfigurationAction;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.config.PerLabelOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Action that represents some kind of C++ compilation step.
 */
@ThreadCompatible
public class CppCompileAction extends ConfigurationAction implements IncludeScannable {
  /**
   * Represents logic that determines which artifacts, if any, should be added to the actual inputs
   * for each included file (in addition to the included file itself)
   */
  public interface IncludeResolver {
    /**
     * Returns the set of files to be added for an included file (as returned in the .d file)
     */
    Iterable<Artifact> getInputsForIncludedFile(
        Artifact includedFile, ArtifactResolver artifactResolver);
  }

  public static final IncludeResolver VOID_INCLUDE_RESOLVER = new IncludeResolver() {
    @Override
    public Iterable<Artifact> getInputsForIncludedFile(Artifact includedFile,
        ArtifactResolver artifactResolver) {
      return ImmutableList.of();
    }
  };

  private static final int VALIDATION_DEBUG = 0;  // 0==none, 1==warns/errors, 2==all
  private static final boolean VALIDATION_DEBUG_WARN = VALIDATION_DEBUG >= 1;

  protected final Artifact outputFile;
  private final Label sourceLabel;
  private final Artifact dwoFile;
  private final NestedSet<Artifact> optionalInputs;
  private final NestedSet<Artifact> mandatoryInputs;
  private final CppCompilationContext context;
  private final Collection<PathFragment> extraSystemIncludePrefixes;
  private final CppCompileCommandLine cppCompileCommandLine;
  private final boolean enableModules;

  @VisibleForTesting
  final CppConfiguration cppConfiguration;
  private final Class<? extends CppCompileActionContext> actionContext;
  private final IncludeResolver includeResolver;

  /**
   * Identifier for the actual execution time behavior of the action.
   *
   * <p>Required because the behavior of this class can be modified by injecting code in the
   * constructor or by inheritance, and we want to have different cache keys for those.
   */
  private final UUID actionClassId;

  private boolean inputsKnown = false;

  /**
   * Creates a new action to compile C/C++ source files.
   *
   * @param owner the owner of the action, usually the configured target that
   *        emitted it
   * @param sourceFile the source file that should be compiled. {@code mandatoryInputs} must
   *        contain this file
   * @param sourceLabel the label of the rule the source file is generated by
   * @param mandatoryInputs any additional files that need to be present for the
   *        compilation to succeed, can be empty but not null, for example, extra sources for FDO.
   * @param outputFile the object file that is written as result of the
   *        compilation, or the fake object for {@link FakeCppCompileAction}s
   * @param dotdFile the .d file that is generated as a side-effect of
   *        compilation
   * @param gcnoFile the coverage notes that are written in coverage mode, can
   *        be null
   * @param dwoFile the .dwo output file where debug information is stored for Fission
   *        builds (null if Fission mode is disabled)
   * @param configuration the build configurations
   * @param context the compilation context
   * @param copts options for the compiler
   * @param coptsFilter regular expression to remove options from {@code copts}
   */
  protected CppCompileAction(ActionOwner owner,
      ImmutableList<String> features,
      Artifact sourceFile,
      Label sourceLabel,
      NestedSet<Artifact> mandatoryInputs,
      Artifact outputFile,
      DotdFile dotdFile,
      @Nullable Artifact gcnoFile,
      @Nullable Artifact dwoFile,
      NestedSet<Artifact> optionalInputs,
      BuildConfiguration configuration,
      CppConfiguration cppConfiguration,
      CppCompilationContext context,
      Class<? extends CppCompileActionContext> actionContext,
      ImmutableList<String> copts,
      ImmutableList<String> pluginOpts,
      Predicate<String> coptsFilter,
      ImmutableList<PathFragment> extraSystemIncludePrefixes,
      boolean enableModules,
      @Nullable String fdoBuildStamp,
      IncludeResolver includeResolver,
      UUID actionClassId) {
    // getInputs() method is overridden in this class so we pass a dummy empty
    // list to the AbstractAction constructor in place of a real input collection.
    super(owner,
          Artifact.NO_ARTIFACTS,
          CollectionUtils.asListWithoutNulls(outputFile, dotdFile.artifact(),
              gcnoFile, dwoFile),
          configuration);
    this.sourceLabel = sourceLabel;
    this.outputFile = Preconditions.checkNotNull(outputFile);
    this.dwoFile = dwoFile;
    this.optionalInputs = optionalInputs;
    this.context = context;
    this.extraSystemIncludePrefixes = extraSystemIncludePrefixes;
    this.enableModules = enableModules;
    this.includeResolver = includeResolver;
    this.cppConfiguration = cppConfiguration;
    if (cppConfiguration != null && !cppConfiguration.shouldScanIncludes()) {
      inputsKnown = true;
    }
    this.cppCompileCommandLine = new CppCompileCommandLine(sourceFile, dotdFile,
        context.getCppModuleMap(), copts, coptsFilter, pluginOpts,
        (gcnoFile != null), features, fdoBuildStamp);
    this.actionContext = actionContext;
    this.actionClassId = actionClassId;

    // We do not need to include the middleman artifact since it is a generated
    // artifact and will definitely exist prior to this action execution.
    this.mandatoryInputs = mandatoryInputs;
    setInputs(createInputs(mandatoryInputs, context.getCompilationPrerequisites(),
        optionalInputs));
  }

  private static NestedSet<Artifact> createInputs(
      NestedSet<Artifact> mandatoryInputs,
      Set<Artifact> prerequisites, NestedSet<Artifact> optionalInputs) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    builder.addTransitive(optionalInputs);
    builder.addAll(prerequisites);
    builder.addTransitive(mandatoryInputs);
    return builder.build();
  }

  @Override
  public List<PathFragment> getBuiltInIncludeDirectories() {
    return cppConfiguration.getBuiltInIncludeDirectories();
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public boolean inputsKnown() {
    return inputsKnown;
  }

  @Override
  public boolean discoversInputs() {
    return true;
  }

  @Override
  public Artifact getPrimaryInput() {
    return getSourceFile();
  }

  @Override
  public Artifact getPrimaryOutput() {
    return getOutputFile();
  }

  /**
   * Returns the path of the c/cc source for gcc.
   */
  public final Artifact getSourceFile() {
    return cppCompileCommandLine.sourceFile;
  }

  /**
   * Returns the path where gcc should put its result.
   */
  public Artifact getOutputFile() {
    return outputFile;
  }

  /**
   * Returns the path of the debug info output file (when debug info is
   * spliced out of the .o file via fission).
   */
  @Nullable
  Artifact getDwoFile() {
    return dwoFile;
  }

  protected PathFragment getInternalOutputFile() {
    return outputFile.getExecPath();
  }

  @VisibleForTesting
  public List<String> getPluginOpts() {
    return cppCompileCommandLine.pluginOpts;
  }

  Collection<PathFragment> getExtraSystemIncludePrefixes() {
    return extraSystemIncludePrefixes;
  }

  @Override
  public Map<Path, Path> getLegalGeneratedScannerFileMap() {
    Map<Path, Path> legalOuts = new HashMap<>();

    for (Artifact a : context.getDeclaredIncludeSrcs()) {
      if (!a.isSourceArtifact()) {
        legalOuts.put(a.getPath(), null);
      }
    }
    for (Pair<Artifact, Artifact> pregreppedSrcs : context.getPregreppedHeaders()) {
      Artifact hdr = pregreppedSrcs.getFirst();
      Preconditions.checkState(!hdr.isSourceArtifact(), hdr);
      legalOuts.put(hdr.getPath(), pregreppedSrcs.getSecond().getPath());
    }
    return Collections.unmodifiableMap(legalOuts);
  }

  /**
   * Returns the path where gcc should put the discovered dependency
   * information.
   */
  public DotdFile getDotdFile() {
    return cppCompileCommandLine.dotdFile;
  }

  protected boolean needsIncludeScanning(Executor executor) {
    return executor.getContext(actionContext).needsIncludeScanning();
  }

  @Override
  public String describeStrategy(Executor executor) {
    return executor.getContext(actionContext).strategyLocality();
  }

  @VisibleForTesting
  public CppCompilationContext getContext() {
    return context;
  }

  @Override
  public List<PathFragment> getQuoteIncludeDirs() {
    return context.getQuoteIncludeDirs();
  }

  @Override
  public List<PathFragment> getIncludeDirs() {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    for (PathFragment fragment : context.getIncludeDirs()) {
      result.add(fragment);
    }
    for (String opt : cppCompileCommandLine.copts) {
      if (opt.startsWith("-I") && opt.length() > 2) {
        // We insist on the combined form "-Idir".
        result.add(new PathFragment(opt.substring(2)));
      }
    }
    return result.build();
  }

  @Override
  public List<PathFragment> getSystemIncludeDirs() {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    for (PathFragment fragment : context.getSystemIncludeDirs()) {
      result.add(fragment);
    }
    for (String opt : cppCompileCommandLine.copts) {
      if (opt.startsWith("-isystem") && opt.length() > 8) {
        // We insist on the combined form "-isystemdir".
        result.add(new PathFragment(opt.substring(8)));
      }
    }
    return result.build();
  }

  @Override
  public List<String> getCmdlineIncludes() {
    ImmutableList.Builder<String> cmdlineIncludes = ImmutableList.builder();
    List<String> args = getArgv();
    for (Iterator<String> argi = args.iterator(); argi.hasNext();) {
      String arg = argi.next();
      if (arg.equals("-include") && argi.hasNext()) {
        cmdlineIncludes.add(argi.next());
      }
    }
    return cmdlineIncludes.build();
  }

  @Override
  public List<PathFragment> getIncludeScannerSources() {
    return ImmutableList.of(getSourceFile().getExecPath());
  }

  @Override
  public List<IncludeScannable> getAuxiliaryScannables() {
    // It is not always enough to scan the main source file.
    // We also need to scan all auxiliary source files.

    ImmutableList.Builder<IncludeScannable> tasks = ImmutableList.builder();
    for (PathFragment source : Artifact.asPathFragments(FileType.filter(getMandatoryInputs(),
        CppFileTypes.C_SOURCE, CppFileTypes.CPP_SOURCE,
        CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR))) {
      if (!source.equals(getSourceFile().getExecPath())) {
        IncludeScannable scanTask =
            configuration.getFragment(CppConfiguration.class).getFdoSupport().getScannable(source);
        if (scanTask != null) {
          tasks.add(scanTask);
        }
      }
    }
    return tasks.build();
  }

  /**
   * Returns the list of "-D" arguments that should be used by this gcc
   * invocation. Only used for testing.
   */
  @VisibleForTesting
  public ImmutableCollection<String> getDefines() {
    return context.getDefines();
  }

  /**
   * Returns an (immutable) map of environment key, value pairs to be
   * provided to the C++ compiler.
   */
  public ImmutableMap<String, String> getEnvironment() {
    Map<String, String> environment =
        new LinkedHashMap<>(configuration.getDefaultShellEnvironment());
    if (configuration.isCodeCoverageEnabled()) {
      environment.put("PWD", "/proc/self/cwd");
    }
    return ImmutableMap.copyOf(environment);
  }

  /**
   * Returns a new, mutable list of command and arguments (argv) to be passed
   * to the gcc subprocess.
   */
  public final List<String> getArgv() {
    return getArgv(getInternalOutputFile());
  }

  protected final List<String> getArgv(PathFragment outputFile) {
    return cppCompileCommandLine.getArgv(outputFile);
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo() {
    CppCompileInfo.Builder info = CppCompileInfo.newBuilder();
    info.setTool(cppConfiguration.getToolPathFragment(Tool.GCC).getPathString());
    for (String option : getCompilerOptions()) {
      info.addCompilerOption(option);
    }
    info.setOutputFile(outputFile.getExecPathString());
    info.setSourceFile(getSourceFile().getExecPathString());
    if (inputsKnown()) {
      info.addAllSourcesAndHeaders(Artifact.toExecPaths(getInputs()));
    } else {
      info.addSourcesAndHeaders(getSourceFile().getExecPathString());
      info.addAllSourcesAndHeaders(
          Artifact.toExecPaths(context.getDeclaredIncludeSrcs()));
    }

    return super.getExtraActionInfo()
        .setExtension(CppCompileInfo.cppCompileInfo, info.build());
  }

  /**
   * Returns the compiler options.
   */
  @VisibleForTesting
  public List<String> getCompilerOptions() {
    return cppCompileCommandLine.getCompilerOptions();
  }

  /**
   * Enforce that the includes actually visited during the compile were properly
   * declared in the rules.
   *
   * <p>The technique is to walk through all of the reported includes that gcc
   * emits into the .d file, and verify that they came from acceptable
   * relative include directories. This is done in two steps:
   *
   * <p>First, each included file is stripped of any include path prefix from
   * {@code quoteIncludeDirs} to produce an effective relative include dir+name.
   *
   * <p>Second, the remaining directory is looked up in {@code declaredIncludeDirs},
   * a list of acceptable dirs. This list contains a set of dir fragments that
   * have been calculated by the configured target to be allowable for inclusion
   * by this source. If no match is found, an error is reported and an exception
   * is thrown.
   *
   * @throws ActionExecutionException iff there was an undeclared dependency
   */
  @VisibleForTesting
  public void validateInclusions(
      MiddlemanExpander middlemanExpander, EventHandler eventHandler)
      throws ActionExecutionException {
    if (!cppConfiguration.shouldScanIncludes() || !inputsKnown()) {
      return;
    }

    IncludeProblems errors = new IncludeProblems();
    IncludeProblems warnings = new IncludeProblems();
    Set<Artifact> allowedIncludes = new HashSet<>();
    for (Artifact input : mandatoryInputs) {
      if (input.isMiddlemanArtifact()) {
        middlemanExpander.expand(input, allowedIncludes);
      }
      allowedIncludes.add(input);
    }

    Iterables.addAll(allowedIncludes, optionalInputs);
    List<PathFragment> cxxSystemIncludeDirs =
        cppConfiguration.getBuiltInIncludeDirectories();
    Iterable<PathFragment> ignoreDirs = Iterables.concat(cxxSystemIncludeDirs,
        extraSystemIncludePrefixes, context.getSystemIncludeDirs());

    // Copy the sets to hash sets for fast contains checking.
    // Avoid immutable sets here to limit memory churn.
    Set<PathFragment> declaredIncludeDirs = Sets.newHashSet(context.getDeclaredIncludeDirs());
    Set<PathFragment> warnIncludeDirs = Sets.newHashSet(context.getDeclaredIncludeWarnDirs());
    Set<Artifact> declaredIncludeSrcs = Sets.newHashSet(context.getDeclaredIncludeSrcs());
    for (Artifact input : getInputs()) {
      if (context.getCompilationPrerequisites().contains(input)
          || allowedIncludes.contains(input)) {
        continue; // ignore our fixed source in mandatoryInput: we just want includes
      }
      // Ignore headers from built-in include directories.
      if (FileSystemUtils.startsWithAny(input.getExecPath(), ignoreDirs)) {
        continue;
      }
      if (!isDeclaredIn(input, declaredIncludeDirs, declaredIncludeSrcs)) {
        // This call can never match the declared include sources (they would be matched above).
        // There are no declared include sources we need to warn about, so use an empty set here.
        if (isDeclaredIn(input, warnIncludeDirs, ImmutableSet.<Artifact>of())) {
          warnings.add(input.getPath().toString());
        } else {
          errors.add(input.getPath().toString());
        }
      }
    }
    if (VALIDATION_DEBUG_WARN) {
      synchronized (System.err) {
        if (VALIDATION_DEBUG >= 2 || errors.hasProblems() || warnings.hasProblems()) {
          if (errors.hasProblems()) {
            System.err.println("ERROR: Include(s) were not in declared srcs:");
          } else if (warnings.hasProblems()) {
            System.err.println("WARN: Include(s) were not in declared srcs:");
          } else {
            System.err.println("INFO: Include(s) were OK for '" + getSourceFile()
                + "', declared srcs:");
          }
          for (Artifact a : context.getDeclaredIncludeSrcs()) {
            System.err.println("  '" + a.toDetailString() + "'");
          }
          System.err.println(" or under declared dirs:");
          for (PathFragment f : Sets.newTreeSet(context.getDeclaredIncludeDirs())) {
            System.err.println("  '" + f + "'");
          }
          System.err.println(" or under declared warn dirs:");
          for (PathFragment f : Sets.newTreeSet(context.getDeclaredIncludeWarnDirs())) {
            System.err.println("  '" + f + "'");
          }
          System.err.println(" with prefixes:");
          for (PathFragment dirpath : context.getQuoteIncludeDirs()) {
            System.err.println("  '" + dirpath + "'");
          }
        }
      }
    }

    if (warnings.hasProblems()) {
      eventHandler.handle(
          new Event(EventKind.WARNING, 
              getOwner().getLocation(), warnings.getMessage(this, getSourceFile()),
          Label.print(getOwner().getLabel())));
    }
    errors.assertProblemFree(this, getSourceFile());
  }

  /**
   * Returns true if an included artifact is declared in a set of allowed
   * include directories. The simple case is that the artifact's parent
   * directory is contained in the set, or is empty.
   *
   * <p>This check also supports a wildcard suffix of '**' for the cases where the
   * calculations are inexact.
   *
   * <p>It also handles unseen non-nested-package subdirs by walking up the path looking
   * for matches.
   */
  private static boolean isDeclaredIn(Artifact input, Set<PathFragment> declaredIncludeDirs,
                                      Set<Artifact> declaredIncludeSrcs) {
    // First check if it's listed in "srcs". If so, then its declared & OK.
    if (declaredIncludeSrcs.contains(input)) {
      return true;
    }
    // If it's a derived artifact, then it MUST be listed in "srcs" as checked above.
    // We define derived here as being not source and not under the include link tree.
    if (!input.isSourceArtifact()
        && !input.getRoot().getExecPath().getBaseName().equals("include")) {
      return false;
    }
    // Need to do dir/package matching: first try a quick exact lookup.
    PathFragment includeDir = input.getRootRelativePath().getParentDirectory();
    if (includeDir.segmentCount() == 0 || declaredIncludeDirs.contains(includeDir)) {
      return true;  // OK: quick exact match.
    }
    // Not found in the quick lookup: try the wildcards.
    for (PathFragment declared : declaredIncludeDirs) {
      if (declared.getBaseName().equals("**")) {
        if (includeDir.startsWith(declared.getParentDirectory())) {
          return true;  // OK: under a wildcard dir.
        }
      }
    }
    // Still not found: see if it is in a subdir of a declared package.
    Path root = input.getRoot().getPath();
    for (Path dir = input.getPath().getParentDirectory();;) {
      if (dir.getRelative("BUILD").exists()) {
        return false;  // Bad: this is a sub-package, not a subdir of a declared package.
      }
      dir = dir.getParentDirectory();
      if (dir.equals(root)) {
        return false;  // Bad: at the top, give up.
      }
      if (declaredIncludeDirs.contains(dir.relativeTo(root))) {
        return true;  // OK: found under a declared dir.
      }
    }
  }

  /**
   * Recalculates this action's live input collection, including sources, middlemen.
   *
   * @throws ActionExecutionException iff any errors happen during update.
   */
  @VisibleForTesting
  @ThreadCompatible
  public final void updateActionInputs(Path execRoot,
      ArtifactResolver artifactResolver, CppCompileActionContext.Reply reply)
      throws ActionExecutionException {
    if (!cppConfiguration.shouldScanIncludes()) {
      return;
    }
    inputsKnown = false;
    NestedSetBuilder<Artifact> inputs = NestedSetBuilder.stableOrder();
    Profiler.instance().startTask(ProfilerTask.ACTION_UPDATE, this);
    try {
      inputs.addTransitive(mandatoryInputs);
      inputs.addTransitive(optionalInputs);
      inputs.addAll(context.getCompilationPrerequisites());
      populateActionInputs(execRoot, artifactResolver, reply, inputs);
      inputsKnown = true;
    } finally {
      Profiler.instance().completeTask(ProfilerTask.ACTION_UPDATE);
      synchronized (this) {
        setInputs(inputs.build());
      }
    }
  }

  private DependencySet processDepset(Path execRoot, CppCompileActionContext.Reply reply)
      throws IOException {
    DependencySet depSet = new DependencySet(execRoot);

    if (getDotdFile().artifact() != null) {
      return depSet.read(getDotdFile().getPath());
    }

    // artifact() is null if we are not using in-memory .d files. We also want to prepare for the
    // case where we expected an in-memory .d file, but we did not get an appropriate response.
    // Perhaps we produced the file locally.
    if (reply == null) {
      return depSet.process(new String(
          FileSystemUtils.readContentAsLatin1(getDotdFile().getPath())));
    } else {
      // This is an in-memory .d file.
      return depSet.process(reply.getContents());
    }
  }

  /**
   * Populates the given ordered collection with additional input artifacts
   * relevant to the specific action implementation.
   *
   * <p>The default implementation updates this Action's input set by reading
   * dynamically-discovered dependency information out of the .d file.
   *
   * <p>Artifacts are considered inputs but not "mandatory" inputs.
   *
   *
   * @param reply the reply from the compilation.
   * @param inputs the ordered collection of inputs to append to
   * @throws ActionExecutionException iff the .d is missing, malformed or has
   *         unresolvable included artifacts.
   */
  @ThreadCompatible
  private void populateActionInputs(Path execRoot,
      ArtifactResolver artifactResolver, CppCompileActionContext.Reply reply,
      NestedSetBuilder<Artifact> inputs)
      throws ActionExecutionException {
    try {
      // Read .d file.
      DependencySet depSet = processDepset(execRoot, reply);

      // Determine prefixes of allowed absolute inclusions.
      CppConfiguration toolchain = cppConfiguration;
      List<PathFragment> systemIncludePrefixes = new ArrayList<>();
      for (PathFragment includePath : toolchain.getBuiltInIncludeDirectories()) {
        if (includePath.isAbsolute()) {
          systemIncludePrefixes.add(includePath);
        }
      }
      systemIncludePrefixes.addAll(extraSystemIncludePrefixes);

      // Check inclusions.
      IncludeProblems problems = new IncludeProblems();
      Map<PathFragment, Artifact> allowedDerivedInputsMap = getAllowedDerivedInputsMap();
      for (PathFragment execPath : depSet.getDependencies()) {
        if (execPath.isAbsolute()) {
          // Absolute includes from system paths are ignored.
          if (FileSystemUtils.startsWithAny(execPath, systemIncludePrefixes)) {
            continue;
          }
          // Since gcc is given only relative paths on the command line,
          // non-system include paths here should never be absolute. If they
          // are, it's probably due to a non-hermetic #include, & we should stop
          // the build with an error.
          if (execPath.startsWith(execRoot.asFragment())) {
            execPath = execPath.relativeTo(execRoot.asFragment()); // funky but tolerable path
          } else {
            problems.add(execPath.getPathString());
            continue;
          }
        }
        Artifact artifact = allowedDerivedInputsMap.get(execPath);
        if (artifact == null) {
          artifact = artifactResolver.resolveSourceArtifact(execPath);
        }
        if (artifact != null) {
          inputs.add(artifact);
          // In some cases, execution backends need extra files for each included file. Add them
          // to the set of actual inputs.
          inputs.addAll(includeResolver.getInputsForIncludedFile(artifact, artifactResolver));
        } else {
          // Abort if we see files that we can't resolve, likely caused by
          // undeclared includes or illegal include constructs.
          problems.add(execPath.getPathString());
        }
      }
      problems.assertProblemFree(this, getSourceFile());
    } catch (IOException e) {
      // Some kind of IO or parse exception--wrap & rethrow it to stop the build.
      throw new ActionExecutionException("error while parsing .d file", e, this, false);
    }
  }

  @Override
  public void updateInputsFromCache(
      ArtifactResolver artifactResolver, Collection<PathFragment> inputPaths) {
    // Note that this method may trigger a violation of the desirable invariant that getInputs()
    // is a superset of getMandatoryInputs(). See bug about an "action not in canonical form"
    // error message and the integration test test_crosstool_change_and_failure().

    Map<PathFragment, Artifact> allowedDerivedInputsMap = getAllowedDerivedInputsMap();
    List<Artifact> inputs = new ArrayList<>();
    for (PathFragment execPath : inputPaths) {
      // The artifact may be a derived artifact, and if it has been created already, then we still
      // want to keep it to preserve incrementality.
      Artifact artifact = allowedDerivedInputsMap.get(execPath);
      if (artifact == null) {
        artifact = artifactResolver.resolveSourceArtifact(execPath);
      }
      // If PathFragment cannot be resolved into the artifact - ignore it. This could happen if
      // rule definition has changed and action no longer depends on, e.g., additional source file
      // in the separate package and that package is no longer referenced anywhere else.
      // It is safe to ignore such paths because dependency checker would identify change in inputs
      // (ignored path was used before) and will force action execution.
      if (artifact != null) {
        inputs.add(artifact);
      }
    }
    inputsKnown = true;
    synchronized (this) {
      setInputs(inputs);
    }
  }

  private Map<PathFragment, Artifact> getAllowedDerivedInputsMap() {
    Map<PathFragment, Artifact> allowedDerivedInputMap = new HashMap<>();
    addToMap(allowedDerivedInputMap, mandatoryInputs);
    addToMap(allowedDerivedInputMap, context.getDeclaredIncludeSrcs());
    addToMap(allowedDerivedInputMap, context.getCompilationPrerequisites());
    Artifact artifact = getSourceFile();
    if (!artifact.isSourceArtifact()) {
      allowedDerivedInputMap.put(artifact.getExecPath(), artifact);
    }
    return allowedDerivedInputMap;
  }

  private void addToMap(Map<PathFragment, Artifact> map, Iterable<Artifact> artifacts) {
    for (Artifact artifact : artifacts) {
      if (!artifact.isSourceArtifact()) {
        map.put(artifact.getExecPath(), artifact);
      }
    }
  }

  @Override
  protected String getRawProgressMessage() {
    return "Compiling " + getSourceFile().prettyPrint();
  }

  /**
   * Return the directories in which to look for headers (pertains to headers
   * not specifically listed in {@code declaredIncludeSrcs}). The return value
   * may contain duplicate elements.
   */
  public NestedSet<PathFragment> getDeclaredIncludeDirs() {
    return context.getDeclaredIncludeDirs();
  }

  /**
   * Return the directories in which to look for headers and issue a warning.
   * (pertains to headers not specifically listed in {@code
   * declaredIncludeSrcs}). The return value may contain duplicate elements.
   */
  public NestedSet<PathFragment> getDeclaredIncludeWarnDirs() {
    return context.getDeclaredIncludeWarnDirs();
  }

  /**
   * Return explicit header files (i.e., header files explicitly listed). The
   * return value may contain duplicate elements.
   */
  public NestedSet<Artifact> getDeclaredIncludeSrcs() {
    return context.getDeclaredIncludeSrcs();
  }

  /**
   * Return explicit header files (i.e., header files explicitly listed) in an order
   * that is stable between builds.
   */
  protected final List<PathFragment> getDeclaredIncludeSrcsInStableOrder() {
    List<PathFragment> paths = new ArrayList<>();
    for (Artifact declaredIncludeSrc : context.getDeclaredIncludeSrcs()) {
      paths.add(declaredIncludeSrc.getExecPath());
    }
    Collections.sort(paths); // Order is not important, but stability is.
    return paths;
  }

  @Override
  public ResourceSet estimateResourceConsumption(Executor executor) {
    return executor.getContext(actionContext).estimateResourceConsumption(this);
  }

  @VisibleForTesting
  public Class<? extends CppCompileActionContext> getActionContext() {
    return actionContext;
  }

  /**
   * Estimate resource consumption when this action is executed locally.
   */
  public ResourceSet estimateResourceConsumptionLocal() {
    // We use a local compile, so much of the time is spent waiting for IO,
    // but there is still significant CPU; hence we estimate 50% cpu usage.
    return new ResourceSet(/*memoryMb=*/200, /*cpuUsage=*/0.5, /*ioUsage=*/0.0);
  }

  @Override
  public String computeKey() {
    Fingerprint f = new Fingerprint();
    f.addUUID(actionClassId);
    f.addStrings(getArgv());

    /*
     * getArgv() above captures all changes which affect the compilation
     * command and hence the contents of the object file.  But we need to
     * also make sure that we reexecute the action if any of the fields
     * that affect whether validateIncludes() will report an error or warning
     * have changed, otherwise we might miss some errors.
     */
    f.addPaths(context.getDeclaredIncludeDirs());
    f.addPaths(context.getDeclaredIncludeWarnDirs());
    f.addPaths(getDeclaredIncludeSrcsInStableOrder());
    f.addPaths(getExtraSystemIncludePrefixes());
    return f.hexDigest();
  }

  @Override
  @ThreadCompatible
  public void execute(
      ActionExecutionContext actionExecutionContext)
          throws ActionExecutionException, InterruptedException {
    Executor executor = actionExecutionContext.getExecutor();
    CppCompileActionContext.Reply reply;
    try {
      reply = executor.getContext(actionContext).execWithReply(this, actionExecutionContext);
    } catch (ExecException e) {
      throw e.toActionExecutionException("C++ compilation of rule '" + getOwner().getLabel() + "'",
          executor.getVerboseFailures(), this);
    }
    ensureCoverageNotesFilesExist();
    IncludeScanningContext scanningContext = executor.getContext(IncludeScanningContext.class);
    updateActionInputs(executor.getExecRoot(), scanningContext.getArtifactResolver(), reply);
    reply = null; // Clear in-memory .d files early.
    validateInclusions(actionExecutionContext.getMiddlemanExpander(), executor.getEventHandler());
  }

  /**
   * Gcc only creates ".gcno" files if the compilation unit is non-empty.
   * To ensure that the set of outputs for a CppCompileAction remains consistent
   * and doesn't vary dynamically depending on the _contents_ of the input files,
   * we create empty ".gcno" files if gcc didn't create them.
   */
  private void ensureCoverageNotesFilesExist() throws ActionExecutionException {
    for (Artifact output : getOutputs()) {
      if (CppFileTypes.COVERAGE_NOTES.matches(output.getFilename()) // ".gcno"
          && !output.getPath().exists()) {
        try {
          FileSystemUtils.createEmptyFile(output.getPath());
        } catch (IOException e) {
          throw new ActionExecutionException(
              "Error creating file '" + output.getPath() + "': " + e.getMessage(), e, this, false);
        }
      }
    }
  }

  /**
   * Provides list of include files needed for performing extra actions on this action when run
   * remotely. The list of include files is created by performing a header scan on the known input
   * files.
   */
  @Override
  public Collection<String> getAdditionalFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return actionExecutionContext.getExecutor().getContext(actionContext)
        .getScannedIncludeFiles(this, actionExecutionContext);
  }

  @Override
  public String getMnemonic() { return "CppCompile"; }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    message.append("  Command: ");
    message.append(
        ShellEscaper.escapeString(cppConfiguration.getLdExecutable().getPathString()));
    message.append('\n');
    // Outputting one argument per line makes it easier to diff the results.
    for (String argument : ShellEscaper.escapeAll(getArgv())) {
      message.append("  Argument: ");
      message.append(argument);
      message.append('\n');
    }

    for (PathFragment path : context.getDeclaredIncludeDirs()) {
      message.append("  Declared include directory: ");
      message.append(ShellEscaper.escapeString(path.getPathString()));
      message.append('\n');
    }

    for (PathFragment path : getDeclaredIncludeSrcsInStableOrder()) {
      message.append("  Declared include source: ");
      message.append(ShellEscaper.escapeString(path.getPathString()));
      message.append('\n');
    }

    for (PathFragment path : getExtraSystemIncludePrefixes()) {
      message.append("  Extra system include prefix: ");
      message.append(ShellEscaper.escapeString(path.getPathString()));
      message.append('\n');
    }
    return message.toString();
  }

  /**
   * The compile command line for the enclosing C++ compile action.
   */
  public final class CppCompileCommandLine {
    private final Artifact sourceFile;
    private final DotdFile dotdFile;
    private final CppModuleMap cppModuleMap;
    private final List<String> copts;
    private final Predicate<String> coptsFilter;
    private final List<String> pluginOpts;
    private final boolean isInstrumented;
    private final Collection<String> features;

    // The value of the BUILD_FDO_TYPE macro to be defined on command line
    @Nullable private final String fdoBuildStamp;

    public CppCompileCommandLine(Artifact sourceFile, DotdFile dotdFile, CppModuleMap cppModuleMap,
        ImmutableList<String> copts, Predicate<String> coptsFilter,
        ImmutableList<String> pluginOpts, boolean isInstrumented,
        Collection<String> features, @Nullable String fdoBuildStamp) {
      this.sourceFile = Preconditions.checkNotNull(sourceFile);
      this.dotdFile = Preconditions.checkNotNull(dotdFile);
      this.cppModuleMap = cppModuleMap;
      this.copts = Preconditions.checkNotNull(copts);
      this.coptsFilter = coptsFilter;
      this.pluginOpts = Preconditions.checkNotNull(pluginOpts);
      this.isInstrumented = isInstrumented;
      this.features = Preconditions.checkNotNull(features);
      this.fdoBuildStamp = fdoBuildStamp;
    }

    protected List<String> getArgv(PathFragment outputFile) {
      List<String> commandLine = new ArrayList<>();

      // first: The command name.
      commandLine.add(cppConfiguration.getToolPathFragment(Tool.GCC).getPathString());

      // second: The compiler options.
      commandLine.addAll(getCompilerOptions());

      // third: The file to compile!
      commandLine.add("-c");
      commandLine.add(sourceFile.getExecPathString());

      // finally: The output file. (Prefixed with -o).
      commandLine.add("-o");
      commandLine.add(outputFile.getPathString());

      return commandLine;
    }

    public List<String> getCompilerOptions() {
      List<String> options = new ArrayList<>();

      if (CppFileTypes.CPP_HEADER.matches(sourceFile.getPath())) {
        // TODO(bazel-team): Read the compiler flag settings out of the CROSSTOOL file.
        // TODO(bazel-team): Handle C headers that probably don't work in C++ mode.
        if (features.contains("parse_headers")) {
          options.add("-x");
          options.add("c++-header");
        } else if (features.contains("preprocess_headers")) {
          options.add("-E");
          options.add("-x");
          options.add("c++");
        } else {
          // CcCommon.collectCAndCppSources() ensures we do not add headers to
          // the compilation artifacts unless either 'parse_headers' or
          // 'preprocess_headers' is set.
          throw new IllegalStateException();
        }
      }

      for (PathFragment quoteIncludePath : context.getQuoteIncludeDirs()) {
        // "-iquote" is a gcc-specific option.  For C compilers that don't support "-iquote",
        // we should instead use "-I".
        options.add("-iquote");
        options.add(quoteIncludePath.getSafePathString());
      }
      for (PathFragment includePath : context.getIncludeDirs()) {
        options.add("-I" + includePath.getSafePathString());
      }
      for (PathFragment systemIncludePath : context.getSystemIncludeDirs()) {
        options.add("-isystem");
        options.add(systemIncludePath.getSafePathString());
      }

      CppConfiguration toolchain = cppConfiguration;

      // pluginOpts has to be added before defaultCopts because -fplugin must precede -plugin-arg.
      options.addAll(pluginOpts);
      addFilteredOptions(options, toolchain.getCompilerOptions(features));

      // Enable instrumentation if requested.
      if (isInstrumented) {
        addFilteredOptions(options, ImmutableList.of("-fprofile-arcs", "-ftest-coverage"));
      }

      String sourceFilename = sourceFile.getExecPathString();
      if (CppFileTypes.C_SOURCE.matches(sourceFilename)) {
        addFilteredOptions(options, toolchain.getCOptions());
      }
      if (CppFileTypes.CPP_SOURCE.matches(sourceFilename)
          || CppFileTypes.CPP_HEADER.matches(sourceFilename)) {
        addFilteredOptions(options, toolchain.getCxxOptions(features));
      }

      // Users don't expect the explicit copts to be filtered by coptsFilter, add them verbatim.
      options.addAll(copts);

      for (String warn : cppConfiguration.getCWarns()) {
        options.add("-W" + warn);
      }
      for (String define : context.getDefines()) {
        options.add("-D" + define);
      }

      // Stamp FDO builds with FDO subtype string
      if (fdoBuildStamp != null) {
        options.add("-D" + CppConfiguration.FDO_STAMP_MACRO + "=\"" + fdoBuildStamp + "\"");
      }

      options.addAll(toolchain.getUnfilteredCompilerOptions(features));

      // GCC gives randomized names to symbols which are defined in
      // an anonymous namespace but have external linkage.  To make
      // computation of these deterministic, we want to override the
      // default seed for the random number generator.  It's safe to use
      // any value which differs for all translation units; we use the
      // path to the object file.
      options.add("-frandom-seed=" + outputFile.getExecPathString());

      // Add the options of --per_file_copt, if the label or the base name of the source file
      // matches the specified regular expression filter.
      for (PerLabelOptions perLabelOptions : cppConfiguration.getPerFileCopts()) {
        if ((sourceLabel != null && perLabelOptions.isIncluded(sourceLabel))
            || perLabelOptions.isIncluded(sourceFile)) {
          options.addAll(perLabelOptions.getOptions());
        }
      }

      // Enable <object>.d file generation.
      if (dotdFile != null) {
        // Gcc options:
        //  -MD turns on .d file output as a side-effect (doesn't imply -E)
        //  -MM[D] enables user includes only, not system includes
        //  -MF <name> specifies the dotd file name
        // Issues:
        //  -M[M] alone subverts actual .o output (implies -E)
        //  -M[M]D alone breaks some of the .d naming assumptions
        // This combination gets user and system includes with specified name:
        //  -MD -MF <name>
        options.add("-MD");
        options.add("-MF");
        options.add(dotdFile.getSafeExecPath().getPathString());
      }

      if (cppModuleMap != null && enableModules) {
        options.add("-Xclang-only=-fmodule-maps");
        options.add("-Xclang-only=-fmodules-strict-decluse");
        options.add("-Xclang-only=-fmodule-name=" + cppModuleMap.getName());
        options.add("-Xclang-only=-fmodule-map-file="
            + cppModuleMap.getArtifact().getExecPathString());
      }

      if (FileType.contains(outputFile, CppFileTypes.ASSEMBLER, CppFileTypes.PIC_ASSEMBLER)) {
        options.add("-S");
      } else if (FileType.contains(outputFile, CppFileTypes.PREPROCESSED_C,
          CppFileTypes.PREPROCESSED_CPP, CppFileTypes.PIC_PREPROCESSED_C,
          CppFileTypes.PIC_PREPROCESSED_CPP)) {
        options.add("-E");
      }

      if (cppConfiguration.useFission()) {
        options.add("-gsplit-dwarf");
      }

      return options;
    }

    // For each option in 'in', add it to 'out' unless it is matched by the 'coptsFilter' regexp.
    private void addFilteredOptions(List<String> out, List<String> in) {
      Iterables.addAll(out, Iterables.filter(in, coptsFilter));
    }
  }

  /**
   * A reference to a .d file. There are two modes:
   * <ol>
   *   <li>an Artifact that represents a real on-disk file
   *   <li>just an execPath that refers to a virtual .d file that is not written to disk
   * </ol>
   */
  public static class DotdFile {
    private final Artifact artifact;
    private final PathFragment execPath;

    public DotdFile(Artifact artifact) {
      this.artifact = artifact;
      this.execPath = null;
    }

    public DotdFile(PathFragment execPath) {
      this.artifact = null;
      this.execPath = execPath;
    }

    /**
     * @return the Artifact or null
     */
    public Artifact artifact() {
      return artifact;
    }

    /**
     * @return Gets the execPath regardless of whether this is a real Artifact
     */
    public PathFragment getSafeExecPath() {
      return execPath == null ? artifact.getExecPath() : execPath;
    }

    /**
     * @return the on-disk location of the .d file or null
     */
    public Path getPath() {
      return artifact.getPath();
    }
  }
}