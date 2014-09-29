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
package com.google.devtools.build.lib.actions;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The interface for Action inputs metadata (Digest and size).
 *
 * NOTE: Implementations must be thread safe.
 */
@ThreadSafe
public interface ActionInputFileCache extends ArtifactMetadataRetriever {
  /**
   * Retrieve the size of the file at the given path. Will usually return 0 on failure instead of
   * throwing an IOException. Returns 0 for files inaccessible to user, but available to the
   * execution environment.
   *
   * @param input the input.
   * @return the file size in bytes.
   * @throws IOException on failure.
   */
  long getSizeInBytes(ActionInput input) throws IOException;

  /**
   * Checks if the file is available locally, based on the assumption that previous operations on
   * the ActionInputFileCache would have created a cache entry for it.
   *
   * @param digest the digest to lookup.
   * @return true if the specified digest is backed by a locally-readable file, false otherwise
   */
  boolean contentsAvailableLocally(ByteString digest);

  /**
   * Concrete subclasses must implement this to provide a mapping from digest to file path,
   * based on files previously seen as inputs.
   *
   * @param digest the digest.
   * @return a File path.
   */
  @Nullable
  File getFileFromDigest(ByteString digest) throws IOException;
}