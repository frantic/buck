/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.OutputPath;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ValueTypeInfo&lt;T&gt; provides methods to extract deps, outputs, rulekeys from values of type T.
 */
@SuppressWarnings("unused")
interface ValueTypeInfo<T> {
  default void extractDep(
      T value, InputRuleResolver inputRuleResolver, Consumer<BuildRule> builder) {}

  default void extractOutput(String name, T value, BiConsumer<String, OutputPath> builder) {}

  <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E;
}
