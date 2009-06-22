/**
 * Copyright 2005 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.IOException;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;                              // deprecated

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/** A section of an input file.  Returned by {@link
 * InputFormat#getSplits(FileSystem, JobConf, int)} and passed to
 * InputFormat#getRecordReader(FileSystem,FileSplit,JobConf,Reporter). */
public class FileSplit implements Writable {
  private Path file;
  private long start;
  private long length;
  
  FileSplit() {}

  /** @deprecated Call {@link #FileSplit(Path,long,long)} instead. */
  public FileSplit(File file, long start, long length) {
    this(new Path(file.toString()), start, length);
  }

  /** Constructs a split.
   *
   * @param file the file name
   * @param start the position of the first byte in the file to process
   * @param length the number of bytes in the file to process
   */
  public FileSplit(Path file, long start, long length) {
    this.file = file;
    this.start = start;
    this.length = length;
  }
  
  /** @deprecated Call {@link #getPath()} instead. */
  public File getFile() { return new File(file.toString()); }
  
  /** The file containing this split's data. */
  public Path getPath() { return file; }
  
  /** The position of the first byte in the file to process. */
  public long getStart() { return start; }
  
  /** The number of bytes in the file to process. */
  public long getLength() { return length; }

  public String toString() { return file + ":" + start + "+" + length; }

  ////////////////////////////////////////////
  // Writable methods
  ////////////////////////////////////////////

  public void write(DataOutput out) throws IOException {
    UTF8.writeString(out, file.toString());
    out.writeLong(start);
    out.writeLong(length);
  }
  public void readFields(DataInput in) throws IOException {
    file = new Path(UTF8.readString(in));
    start = in.readLong();
    length = in.readLong();
  }


}