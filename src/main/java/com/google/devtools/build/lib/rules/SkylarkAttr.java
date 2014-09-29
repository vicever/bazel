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

package com.google.devtools.build.lib.rules;

import static com.google.devtools.build.lib.syntax.SkylarkFunction.castList;

import com.google.common.base.Predicate;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.Attribute.SkylarkLateBound;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.SkylarkFileType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin.Param;
import com.google.devtools.build.lib.syntax.SkylarkCallbackFunction;
import com.google.devtools.build.lib.syntax.SkylarkEnvironment;
import com.google.devtools.build.lib.syntax.SkylarkFunction;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkModule;
import com.google.devtools.build.lib.syntax.UserDefinedFunction;
import com.google.devtools.build.lib.util.FileTypeSet;

import java.util.Map;

/**
 * A helper class to provide Attr module in Skylark.
 */
@SkylarkModule(name = "attr", namespace = true, onlyLoadingPhase = true,
    doc = "Module for creating new attributes. "
    + "They are only for use with the <i>rule</i> function.")
public final class SkylarkAttr {
  // TODO(bazel-team): Better check the arguments.

  private static final String MANDATORY_DOC =
      "set to true if users have to explicitely specify the value";

  private static final String ALLOW_FILES_DOC =
      "whether file targets are allowed. Can be True, False (default), or "
      + "a filetype filter.";

  private static final String RULE_CLASSES_DOC =
      "allowed rule classes of the label type attribute. "
      + "For example, use ANY_RULE, NO_RULE, or a list of strings.";

  private static final String FLAGS_DOC =
      "deprecated, will be removed";

  private static final String DEFAULT_DOC =
      "the default value of the attribute";

  private static final String CONFIGURATION_DOC =
      "configuration of the attribute. "
      + "For example, use DATA_CFG or HOST_CFG.";

  private static final String EXECUTABLE_DOC =
      "set to True if the labels have to be executable";

  private static Attribute.Builder<?> createAttribute(Type<?> type, Map<String, Object> arguments,
      FuncallExpression ast, SkylarkEnvironment env) throws EvalException, ConversionException {
    final Location loc = ast.getLocation();
    // We use an empty name now so that we can set it later.
    // This trick makes sense only in the context of Skylark (builtin rules should not use it).
    Attribute.Builder<?> builder = Attribute.attr("", type);

    Object defaultValue = arguments.get("default");
    if (defaultValue != null) {
      if (defaultValue instanceof UserDefinedFunction) {
        // Late bound attribute
        UserDefinedFunction func = (UserDefinedFunction) defaultValue;
        final SkylarkCallbackFunction callback = new SkylarkCallbackFunction(func, ast, env);
        final SkylarkLateBound computedValue;
        if (type.equals(Type.LABEL) || type.equals(Type.LABEL_LIST)) {
          computedValue = new SkylarkLateBound(false, callback);
        } else {
          throw new EvalException(loc, "Only label type attributes can be late bound");
        }
        builder.value(computedValue);
      } else {
        builder.defaultValue(defaultValue);
      }
    }

    for (String flag :
             castList(arguments.get("flags"), String.class, "flags for attribute definition")) {
      builder.setPropertyFlag(flag);
    }

    if (arguments.containsKey("mandatory") && (Boolean) arguments.get("mandatory")) {
      builder.setPropertyFlag("MANDATORY");
    }

    if (arguments.containsKey("executable") && (Boolean) arguments.get("executable")) {
      builder.setPropertyFlag("EXECUTABLE");
    }

    if (arguments.containsKey("single_file") && (Boolean) arguments.get("single_file")) {
      builder.setPropertyFlag("SINGLE_ARTIFACT");
    }

    if (arguments.containsKey("allow_files")) {
      Object fileTypesObj = arguments.get("allow_files");
      if (fileTypesObj == Boolean.TRUE) {
        builder.allowedFileTypes(FileTypeSet.ANY_FILE);
      } else if (fileTypesObj == Boolean.FALSE) {
        builder.allowedFileTypes(FileTypeSet.NO_FILE);
      } else if (fileTypesObj instanceof SkylarkFileType) {
        builder.allowedFileTypes(((SkylarkFileType) fileTypesObj).getFileTypeSet());
      } else {
        throw new EvalException(loc, "allow_files should be a boolean or a filetype object.");
      }
    } else if (type.equals(Type.LABEL) || type.equals(Type.LABEL_LIST)) {
      builder.allowedFileTypes(FileTypeSet.NO_FILE);
    }

    Object ruleClassesObj = arguments.get("rule_classes");
    if (ruleClassesObj == Attribute.ANY_RULE || ruleClassesObj == Attribute.NO_RULE) {
      // This causes an unchecked warning but it's fine because of the surrounding if.
      builder.allowedRuleClasses((Predicate<RuleClass>) ruleClassesObj);
    } else if (ruleClassesObj != null) {
      builder.allowedRuleClasses(castList(ruleClassesObj, String.class,
              "allowed rule classes for attribute definition"));
    }

    if (arguments.containsKey("providers")) {
      builder.mandatoryProviders(castList(arguments.get("providers"),
          String.class, "mandatory providers for attribute definition"));
    }

    if (arguments.containsKey("cfg")) {
      builder.cfg((ConfigurationTransition) arguments.get("cfg"));
    }
    return builder;
  }

  private static Object createAttribute(Map<String, Object> kwargs, Type<?> type,
      FuncallExpression ast, Environment env) throws EvalException {
    try {
      return createAttribute(type, kwargs, ast, (SkylarkEnvironment) env);
    } catch (ConversionException e) {
      throw new EvalException(ast.getLocation(), e.getMessage());
    }
  }

  @SkylarkBuiltin(name = "int", doc = "Creates a rule string class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction integer = new SkylarkFunction("int") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.INTEGER, ast, env);
      }
    };

  @SkylarkBuiltin(name = "string", doc = "Creates a rule string class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction string = new SkylarkFunction("string") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.STRING, ast, env);
      }
    };

  @SkylarkBuiltin(name = "label", doc = "Creates a rule string class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "executable", type = Boolean.class, doc = EXECUTABLE_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "allow_files", doc = ALLOW_FILES_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "providers", type = SkylarkList.class,
          doc = "mandatory providers every dependency has to have"),
      @Param(name = "rule_classes", doc = RULE_CLASSES_DOC),
      @Param(name = "single_file", doc =
          "if true, the label must correspond to a single file. "
          + "Access it through ctx.file.<attribute_name>."),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction label = new SkylarkFunction("label") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.LABEL, ast, env);
      }
    };

  @SkylarkBuiltin(name = "string_list", doc = "Creates a rule string_list class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class,
          doc = CONFIGURATION_DOC)})
  private static SkylarkFunction stringList = new SkylarkFunction("string_list") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.STRING_LIST, ast, env);
      }
    };

  @SkylarkBuiltin(name = "label_list", doc = "Creates a rule label_list class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "executable", type = Boolean.class, doc = EXECUTABLE_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "allow_files", doc = ALLOW_FILES_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "rule_classes", doc = RULE_CLASSES_DOC),
      @Param(name = "providers", type = SkylarkList.class,
          doc = "mandatory providers every dependency has to have"),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction labelList = new SkylarkFunction("label_list") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.LABEL_LIST, ast, env);
      }
    };

  @SkylarkBuiltin(name = "bool", doc = "Creates a rule bool class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction bool = new SkylarkFunction("bool") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.BOOLEAN, ast, env);
      }
    };

  @SkylarkBuiltin(name = "output", doc = "Creates a rule output class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction output = new SkylarkFunction("output") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.OUTPUT, ast, env);
      }
    };

  @SkylarkBuiltin(name = "output_list", doc = "Creates a rule output_list class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction outputList = new SkylarkFunction("output_list") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.OUTPUT_LIST, ast, env);
      }
    };

  @SkylarkBuiltin(name = "license", doc = "Creates a rule license class attribute.",
      objectType = SkylarkAttr.class,
      returnType = Attribute.class,
      optionalParams = {
      @Param(name = "default", doc = DEFAULT_DOC),
      @Param(name = "flags", type = SkylarkList.class, doc = FLAGS_DOC),
      @Param(name = "mandatory", type = Boolean.class, doc = MANDATORY_DOC),
      @Param(name = "cfg", type = ConfigurationTransition.class, doc = CONFIGURATION_DOC)})
  private static SkylarkFunction license = new SkylarkFunction("license") {
      @Override
      public Object call(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException {
        return createAttribute(kwargs, Type.LICENSE, ast, env);
      }
    };
}