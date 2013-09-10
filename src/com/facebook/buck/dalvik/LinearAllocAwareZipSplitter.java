/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.dalvik;

import com.google.common.base.Predicate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/**
 * Alternative to {@link DefaultZipSplitter} that uses estimates from {@link LinearAllocEstimator}
 * to determine how many classes to pack into a dex.
 * <p>
 * It does two passes through the .class files:
 * <ul>
 *   <li>During the first pass, it uses the {@code requiredInPrimaryZip} predicate to filter the set
 *       of classes that <em>must</em> be included in the primary dex. These classes are added to
 *       the primary zip.
 *   </li>During the second pass, classes that were not matched during the initial pass are added to
 *        zips as space allows. This is a simple, greedy algorithm.
 * </ul>
 */
public class LinearAllocAwareZipSplitter extends AbstractZipSplitter {

  /**
   * @see ZipSplitterFactory#newInstance(Set, File, File, String, Predicate, DexSplitStrategy, CanaryStrategy, File)
   */
  private LinearAllocAwareZipSplitter(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long linearAllocLimit,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    super(inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        linearAllocLimit,
        requiredInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
  }

  public static LinearAllocAwareZipSplitter splitZip(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long linearAllocLimit,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    return new LinearAllocAwareZipSplitter(
        inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        linearAllocLimit,
        requiredInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
  }

  @Override
  public Collection<File> execute() throws IOException {
    ClasspathTraverser classpathTraverser = new DefaultClasspathTraverser();

    // Start out by writing the primary zip and recording which entries were added to it.
    primaryOut = newZipOutput(outPrimary);

    // Iterate over all of the inFiles and add all entries that match the requiredInPrimaryZip
    // predicate.
    classpathTraverser.traverse(new ClasspathTraversal(inFiles) {
      @Override
      public void visit(FileLike entry) throws IOException {
        if (requiredInPrimaryZip.apply(entry.getRelativePath())) {
          primaryOut.putEntry(entry);
        }
      }
    });

    // Now that all of the required entries have been added to the primary zip, fill the rest of
    // the zip up with the remaining entries.
    classpathTraverser.traverse(new ClasspathTraversal(inFiles) {
      @Override
      public void visit(FileLike entry) throws IOException {
        if (primaryOut.containsEntry(entry)) {
          return;
        }

        // Even if we have started writing a secondary dex, we still check if there is any leftover
        // room in the primary dex for the current entry in the traversal.
        if (primaryOut.canPutEntry(entry)) {
          primaryOut.putEntry(entry);
        } else {
          getSecondaryZipToWriteTo(entry).putEntry(entry);
        }
      }
    });

    primaryOut.close();
    if (currentSecondaryOut != null) {
      currentSecondaryOut.close();
    }

    return secondaryFiles.build();
  }

  /**
   * Returns the estimated size of the {@code fileLike} in terms of how it affects linear alloc.
   */
  private static long getLinearAllocSizeEstimate(FileLike fileLike) {
    String name = fileLike.getRelativePath();
    if (!name.endsWith(".class")) {
      // Probably something like a pom.properties file in a JAR: this does not contribute
      // to the linear alloc size, so return zero.
      return 0;
    }

    try {
      return LinearAllocEstimator.estimateLinearAllocFootprint(fileLike.getInput());
    } catch (IOException e) {
      throw new RuntimeException(String.format("Error calculating size for %s.", name), e);
    }
  }

  @Override
  protected ZipOutputStreamHelper newZipOutput(File file) throws FileNotFoundException {
    return new ZipOutputStreamHelper(file, zipSizeHardLimit, reportDir) {
      @Override
      long getSize(FileLike fileLike) {
        return getLinearAllocSizeEstimate(fileLike);
      }
    };
  }
}
