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
package com.google.devtools.build.docgen;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.docgen.SkylarkJavaInterfaceExplorer.SkylarkMethod;
import com.google.devtools.build.docgen.SkylarkJavaInterfaceExplorer.SkylarkModuleDoc;
import com.google.devtools.build.lib.packages.MethodLibrary;
import com.google.devtools.build.lib.rules.SkylarkModules;
import com.google.devtools.build.lib.rules.SkylarkRuleContext;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin.Param;
import com.google.devtools.build.lib.syntax.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkModule;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * A class to assemble documentation for Skylark.
 */
public class SkylarkDocumentationProcessor {

  @SkylarkModule(name = "Top level Skylark items and functions",
      doc = "Top level Skylark items and functions")
  private static final class TopLevelModule {}

  static SkylarkModule getTopLevelModule() {
    return TopLevelModule.class.getAnnotation(SkylarkModule.class);
  }

  /**
   * Generates the Skylark documentation to the given output directory.
   */
  public void generateDocumentation(String outputRootDir) throws IOException,
      BuildEncyclopediaDocException {
    BufferedWriter bw = null;
    File skylarkDocPath = new File(outputRootDir + File.separator + DocgenConsts.SKYLARK_DOC_NAME);
    try {
      bw = new BufferedWriter(new FileWriter(skylarkDocPath));
      bw.write(SourceFileReader.readTemplateContents(DocgenConsts.SKYLARK_BODY_TEMPLATE,
          ImmutableMap.<String, String>of(
              DocgenConsts.VAR_SECTION_SKYLARK_BUILTIN, generateAllBuiltinDoc())));
      System.out.println("Skylark documentation generated: " + skylarkDocPath.getAbsolutePath());
    } finally {
      if (bw != null) {
        bw.close();
      }
    }
  }

  @VisibleForTesting
  Map<String, SkylarkModuleDoc> collectModules() {
    Map<String, SkylarkModuleDoc> modules = new TreeMap<>();
    Map<String, SkylarkModuleDoc> builtinModules = collectBuiltinModules();
    Map<SkylarkModule, Class<?>> builtinJavaObjects = collectBuiltinJavaObjects();

    modules.putAll(builtinModules);
    SkylarkJavaInterfaceExplorer explorer = new SkylarkJavaInterfaceExplorer();
    for (SkylarkModuleDoc builtinObject : builtinModules.values()) {
      explorer.collect(builtinObject.getAnnotation(), builtinObject.getClassObject(), modules);
    }
    for (Entry<SkylarkModule, Class<?>> builtinModule : builtinJavaObjects.entrySet()) {
      explorer.collect(builtinModule.getKey(), builtinModule.getValue(), modules);
    }
    return modules;
  }

  private String generateAllBuiltinDoc() {
    Map<String, SkylarkModuleDoc> modules = collectModules();

    StringBuilder sb = new StringBuilder();
    // Generate the top level module first in the doc
    SkylarkModuleDoc topLevelModule = modules.remove(getTopLevelModule().name());
    generateModuleDoc(topLevelModule, sb);
    for (SkylarkModuleDoc module : modules.values()) {
      if (!module.getAnnotation().hidden()) {
        generateModuleDoc(module, sb);
      }
    }
    return sb.toString();
  }

  private void generateModuleDoc(SkylarkModuleDoc module, StringBuilder sb) {
    SkylarkModule annotation = module.getAnnotation();
    sb.append(String.format("<h3 id=\"modules.%s\">%s</h3>\n",
          annotation.name(),
          annotation.name()))
      .append(annotation.doc())
      .append("\n");
    for (SkylarkMethod method : module.getJavaMethods()) {
      generateDirectJavaMethodDoc(annotation.name(), method.name, method.method,
          method.callable, sb);
    }
    for (SkylarkBuiltin builtin : module.getBuiltinMethods().values()) {
      generateBuiltinItemDoc(annotation.name(), builtin, sb);
    }
  }

  private void generateBuiltinItemDoc(
      String moduleName, SkylarkBuiltin annotation, StringBuilder sb) {
    if (annotation.hidden()) {
      return;
    }
    sb.append(String.format("<h4 id=\"modules.%s.%s\">%s</h4>\n",
          moduleName,
          annotation.name(),
          annotation.name()))
      .append(annotation.doc());
    printParams(
        "Mandatory parameters", moduleName, annotation.name(), annotation.mandatoryParams(), sb);
    printParams(
        "Optional parameters", moduleName, annotation.name(), annotation.optionalParams(), sb);
  }

  private void generateDirectJavaMethodDoc(String objectName, String methodName,
      Method method, SkylarkCallable annotation, StringBuilder sb) {
    if (annotation.hidden()) {
      return;
    }

    sb.append(String.format("<h4 id=\"modules.%s.%s\">%s</h4>\n%s\n",
            objectName,
            methodName,
            methodName,
            getSignature(objectName, methodName, method)))
        .append(annotation.doc())
        .append(getReturnTypeExtraMessage(method, annotation))
        .append("\n");
  }

  private String getReturnTypeExtraMessage(Method method, SkylarkCallable annotation) {
    if (method.getReturnType().equals(Void.TYPE)) {
      return " Always returns <code>None</code>.\n";
    }
    if (annotation.allowReturnNones()) {
      return " May return <code>None</code>.\n";
    }
    return "";
  }

  private String getSignature(String objectName, String methodName, Method method) {
    String args = method.getAnnotation(SkylarkCallable.class).structField()
        ? "" : "(" + getParameterString(method) + ")";

    return String.format("<code>%s %s.%s%s</code><br>",
        EvalUtils.getDataTypeNameFromClass(method.getReturnType()), objectName, methodName, args);
  }

  private String getParameterString(Method method) {
    return Joiner.on(", ").join(Iterables.transform(
        ImmutableList.copyOf(method.getParameterTypes()), new Function<Class<?>, String>() {
          @Override
          public String apply(Class<?> input) {
            return EvalUtils.getDataTypeNameFromClass(input);
          }
        }));
  }

  private void printParams(String title, String moduleName, String methodName,
      Param[] params, StringBuilder sb) {
    if (params.length > 0) {
      sb.append(String.format("<h5>%s</h5>\n", title));
      sb.append("<ul>\n");
      for (Param param : params) {
        sb.append(String.format("\t<li id=\"modules.%s.%s.%s\"><code>%s</code>: ",
            moduleName,
            methodName,
            param.name(),
            param.name()))
          .append(param.doc())
          .append("\n\t</li>\n");
      }
      sb.append("</ul>\n");
    }
  }

  private Map<String, SkylarkModuleDoc> collectBuiltinModules() {
    Map<String, SkylarkModuleDoc> modules = new HashMap<>();
    collectBuiltinDoc(modules, Environment.class.getDeclaredFields());
    collectBuiltinDoc(modules, MethodLibrary.class.getDeclaredFields());
    for (Class<?> moduleClass : SkylarkModules.MODULES) {
      collectBuiltinDoc(modules, moduleClass.getDeclaredFields());
    }
    return modules;
  }

  private Map<SkylarkModule, Class<?>> collectBuiltinJavaObjects() {
    Map<SkylarkModule, Class<?>> modules = new HashMap<>();
    collectBuiltinModule(modules, SkylarkRuleContext.class);
    return modules;
  }

  /**
   * Returns the top level modules and functions with their documentation in a command-line
   * printable format.
   */
  public Map<String, String> collectTopLevelModules() {
    Map<String, String> modules = new TreeMap<>();
    for (SkylarkModuleDoc doc : collectBuiltinModules().values()) {
      if (doc.getAnnotation() == getTopLevelModule()) {
        for (Map.Entry<String, SkylarkBuiltin> entry : doc.getBuiltinMethods().entrySet()) {
          if (!entry.getValue().hidden()) {
            modules.put(entry.getKey(), DocgenConsts.toCommandLineFormat(entry.getValue().doc()));
          }
        }
      } else {
        modules.put(doc.getAnnotation().name(),
            DocgenConsts.toCommandLineFormat(doc.getAnnotation().doc()));
      }
    }
    return modules;
  }

  private void collectBuiltinModule(
      Map<SkylarkModule, Class<?>> modules, Class<?> moduleClass) {
    if (moduleClass.isAnnotationPresent(SkylarkModule.class)) {
      SkylarkModule skylarkModule = moduleClass.getAnnotation(SkylarkModule.class);
      modules.put(skylarkModule, moduleClass);
    }
  }

  private void collectBuiltinDoc(Map<String, SkylarkModuleDoc> modules, Field[] fields) {
    for (Field field : fields) {
      if (field.isAnnotationPresent(SkylarkBuiltin.class)) {
        SkylarkBuiltin skylarkBuiltin = field.getAnnotation(SkylarkBuiltin.class);
        Class<?> moduleClass = skylarkBuiltin.objectType();
        SkylarkModule skylarkModule = moduleClass.equals(Object.class)
            ? getTopLevelModule()
            : moduleClass.getAnnotation(SkylarkModule.class);
        if (!modules.containsKey(skylarkModule.name())) {
          modules.put(skylarkModule.name(), new SkylarkModuleDoc(skylarkModule, moduleClass));
        }
        modules.get(skylarkModule.name()).getBuiltinMethods()
            .put(skylarkBuiltin.name(), skylarkBuiltin);
      }
    }
  }
}