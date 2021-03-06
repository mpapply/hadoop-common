/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.shell.Command;
import org.apache.hadoop.fs.shell.CommandFactory;
import org.apache.hadoop.fs.shell.CommandFormat;
import org.apache.hadoop.fs.shell.FsCommand;
import org.apache.hadoop.fs.shell.PathExceptions.PathNotFoundException;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/** Provide command line access to a FileSystem. */
@InterfaceAudience.Private
public class FsShell extends Configured implements Tool {
  
  static final Log LOG = LogFactory.getLog(FsShell.class);

  private FileSystem fs;
  private Trash trash;
  protected CommandFactory commandFactory;

  public static final SimpleDateFormat dateForm = 
    new SimpleDateFormat("yyyy-MM-dd HH:mm");
  static final int BORDER = 2;

  static final String GET_SHORT_USAGE = "-get [-ignoreCrc] [-crc] <src> <localdst>";
  static final String COPYTOLOCAL_SHORT_USAGE = GET_SHORT_USAGE.replace(
      "-get", "-copyToLocal");

  /**
   */
  public FsShell() {
    this(null);
  }

  public FsShell(Configuration conf) {
    super(conf);
    fs = null;
    trash = null;
    commandFactory = new CommandFactory();
  }
  
  protected FileSystem getFS() throws IOException {
    if(fs == null)
      fs = FileSystem.get(getConf());
    
    return fs;
  }
  
  protected Trash getTrash() throws IOException {
    if (this.trash == null) {
      this.trash = new Trash(getConf());
    }
    return this.trash;
  }
  
  protected void init() throws IOException {
    getConf().setQuietMode(true);
  }

  
  /**
   * Copies from stdin to the indicated file.
   */
  private void copyFromStdin(Path dst, FileSystem dstFs) throws IOException {
    if (dstFs.isDirectory(dst)) {
      throw new IOException("When source is stdin, destination must be a file.");
    }
    if (dstFs.exists(dst)) {
      throw new IOException("Target " + dst.toString() + " already exists.");
    }
    FSDataOutputStream out = dstFs.create(dst); 
    try {
      IOUtils.copyBytes(System.in, out, getConf(), false);
    } 
    finally {
      out.close();
    }
  }

  /** 
   * Print from src to stdout.
   */
  private void printToStdout(InputStream in) throws IOException {
    try {
      IOUtils.copyBytes(in, System.out, getConf(), false);
    } finally {
      in.close();
    }
  }

  
  /**
   * Add local files to the indicated FileSystem name. src is kept.
   */
  void copyFromLocal(Path[] srcs, String dstf) throws IOException {
    Path dstPath = new Path(dstf);
    FileSystem dstFs = dstPath.getFileSystem(getConf());
    if (srcs.length == 1 && srcs[0].toString().equals("-"))
      copyFromStdin(dstPath, dstFs);
    else
      dstFs.copyFromLocalFile(false, false, srcs, dstPath);
  }
  
  /**
   * Add local files to the indicated FileSystem name. src is removed.
   */
  void moveFromLocal(Path[] srcs, String dstf) throws IOException {
    Path dstPath = new Path(dstf);
    FileSystem dstFs = dstPath.getFileSystem(getConf());
    dstFs.moveFromLocalFile(srcs, dstPath);
  }

  /**
   * Add a local file to the indicated FileSystem name. src is removed.
   */
  void moveFromLocal(Path src, String dstf) throws IOException {
    moveFromLocal((new Path[]{src}), dstf);
  }

  /**
   * Obtain the indicated files that match the file pattern <i>srcf</i>
   * and copy them to the local name. srcf is kept.
   * When copying multiple files, the destination must be a directory. 
   * Otherwise, IOException is thrown.
   * @param argv : arguments
   * @param pos : Ignore everything before argv[pos]  
   * @throws Exception 
   * @see org.apache.hadoop.fs.FileSystem.globStatus 
   */
  void copyToLocal(String[]argv, int pos) throws Exception {
    CommandFormat cf = new CommandFormat("copyToLocal", 2,2,"crc","ignoreCrc");
    
    String srcstr = null;
    String dststr = null;
    try {
      List<String> parameters = cf.parse(argv, pos);
      srcstr = parameters.get(0);
      dststr = parameters.get(1);
    }
    catch(IllegalArgumentException iae) {
      System.err.println("Usage: java FsShell " + GET_SHORT_USAGE);
      throw iae;
    }
    boolean copyCrc = cf.getOpt("crc");
    final boolean verifyChecksum = !cf.getOpt("ignoreCrc");

    if (dststr.equals("-")) {
      if (copyCrc) {
        System.err.println("-crc option is not valid when destination is stdout.");
      }
      
      List<String> catArgv = new ArrayList<String>();
      catArgv.add("-cat");
      if (cf.getOpt("ignoreCrc")) catArgv.add("-ignoreCrc");
      catArgv.add(srcstr);      
      run(catArgv.toArray(new String[0]));
    } else {
      File dst = new File(dststr);      
      Path srcpath = new Path(srcstr);
      FileSystem srcFS = getSrcFileSystem(srcpath, verifyChecksum);
      if (copyCrc && !(srcFS instanceof ChecksumFileSystem)) {
        System.err.println("-crc option is not valid when source file system " +
            "does not have crc files. Automatically turn the option off.");
        copyCrc = false;
      }
      FileStatus[] srcs = srcFS.globStatus(srcpath);
      if (null == srcs) {
        throw new PathNotFoundException(srcstr);
      }
      boolean dstIsDir = dst.isDirectory(); 
      if (srcs.length > 1 && !dstIsDir) {
        throw new IOException("When copying multiple files, "
                              + "destination should be a directory.");
      }
      for (FileStatus status : srcs) {
        Path p = status.getPath();
        File f = dstIsDir? new File(dst, p.getName()): dst;
        copyToLocal(srcFS, status, f, copyCrc);
      }
    }
  }

  /**
   * Return the {@link FileSystem} specified by src and the conf.
   * It the {@link FileSystem} supports checksum, set verifyChecksum.
   */
  private FileSystem getSrcFileSystem(Path src, boolean verifyChecksum
      ) throws IOException { 
    FileSystem srcFs = src.getFileSystem(getConf());
    srcFs.setVerifyChecksum(verifyChecksum);
    return srcFs;
  }

  /**
   * The prefix for the tmp file used in copyToLocal.
   * It must be at least three characters long, required by
   * {@link java.io.File#createTempFile(String, String, File)}.
   */
  static final String COPYTOLOCAL_PREFIX = "_copyToLocal_";

  /**
   * Copy a source file from a given file system to local destination.
   * @param srcFS source file system
   * @param src source path
   * @param dst destination
   * @param copyCrc copy CRC files?
   * @exception IOException If some IO failed
   */
  private void copyToLocal(final FileSystem srcFS, final FileStatus srcStatus,
                           final File dst, final boolean copyCrc)
    throws IOException {
    /* Keep the structure similar to ChecksumFileSystem.copyToLocal(). 
     * Ideal these two should just invoke FileUtil.copy() and not repeat
     * recursion here. Of course, copy() should support two more options :
     * copyCrc and useTmpFile (may be useTmpFile need not be an option).
     */
    
    Path src = srcStatus.getPath();
    if (srcStatus.isFile()) {
      if (dst.exists()) {
        // match the error message in FileUtil.checkDest():
        throw new IOException("Target " + dst + " already exists");
      }
      
      // use absolute name so that tmp file is always created under dest dir
      File tmp = FileUtil.createLocalTempFile(dst.getAbsoluteFile(),
                                              COPYTOLOCAL_PREFIX, true);
      if (!FileUtil.copy(srcFS, src, tmp, false, srcFS.getConf())) {
        throw new IOException("Failed to copy " + src + " to " + dst); 
      }
      
      if (!tmp.renameTo(dst)) {
        throw new IOException("Failed to rename tmp file " + tmp + 
                              " to local destination \"" + dst + "\".");
      }

      if (copyCrc) {
        if (!(srcFS instanceof ChecksumFileSystem)) {
          throw new IOException("Source file system does not have crc files");
        }
        
        ChecksumFileSystem csfs = (ChecksumFileSystem) srcFS;
        File dstcs = FileSystem.getLocal(srcFS.getConf())
          .pathToFile(csfs.getChecksumFile(new Path(dst.getCanonicalPath())));
        FileSystem fs = csfs.getRawFileSystem();
        FileStatus status = csfs.getFileStatus(csfs.getChecksumFile(src));
        copyToLocal(fs, status, dstcs, false);
      } 
    } else if (srcStatus.isSymlink()) {
      throw new AssertionError("Symlinks unsupported");
    } else {
      // once FileUtil.copy() supports tmp file, we don't need to mkdirs().
      if (!dst.mkdirs()) {
        throw new IOException("Failed to create local destination \"" +
                              dst + "\".");
      }
      for(FileStatus status : srcFS.listStatus(src)) {
        copyToLocal(srcFS, status,
                    new File(dst, status.getPath().getName()), copyCrc);
      }
    }
  }


  /**
   * Obtain the indicated file and copy to the local name.
   * srcf is removed.
   */
  void moveToLocal(String srcf, Path dst) throws IOException {
    System.err.println("Option '-moveToLocal' is not implemented yet.");
  }
    
  /**
   * Move files that match the file pattern <i>srcf</i>
   * to a destination file.
   * When moving mutiple files, the destination must be a directory. 
   * Otherwise, IOException is thrown.
   * @param srcf a file pattern specifying source files
   * @param dstf a destination local file/directory 
   * @throws IOException  
   * @see org.apache.hadoop.fs.FileSystem#globStatus(Path)
   */
  void rename(String srcf, String dstf) throws IOException {
    Path srcPath = new Path(srcf);
    Path dstPath = new Path(dstf);
    FileSystem fs = srcPath.getFileSystem(getConf());
    URI srcURI = fs.getUri();
    URI dstURI = dstPath.getFileSystem(getConf()).getUri();
    if (srcURI.compareTo(dstURI) != 0) {
      throw new IOException("src and destination filesystems do not match.");
    }
    Path[] srcs = FileUtil.stat2Paths(fs.globStatus(srcPath), srcPath);
    Path dst = new Path(dstf);
    if (srcs.length > 1 && !fs.isDirectory(dst)) {
      throw new IOException("When moving multiple files, " 
                            + "destination should be a directory.");
    }
    for(int i=0; i<srcs.length; i++) {
      if (!fs.rename(srcs[i], dst)) {
        FileStatus srcFstatus = null;
        FileStatus dstFstatus = null;
        try {
          srcFstatus = fs.getFileStatus(srcs[i]);
        } catch(FileNotFoundException e) {
          throw new PathNotFoundException(srcs[i].toString());
        }
        try {
          dstFstatus = fs.getFileStatus(dst);
        } catch(IOException e) {
          LOG.debug("Error getting file status of " + dst, e);
        }
        if((srcFstatus!= null) && (dstFstatus!= null)) {
          if (srcFstatus.isDirectory()  && !dstFstatus.isDirectory()) {
            throw new IOException("cannot overwrite non directory "
                + dst + " with directory " + srcs[i]);
          }
        }
        throw new IOException("Failed to rename " + srcs[i] + " to " + dst);
      }
    }
  }

  /**
   * Move/rename file(s) to a destination file. Multiple source
   * files can be specified. The destination is the last element of
   * the argvp[] array.
   * If multiple source files are specified, then the destination 
   * must be a directory. Otherwise, IOException is thrown.
   * @exception: IOException  
   */
  private int rename(String argv[], Configuration conf) throws IOException {
    int i = 0;
    int exitCode = 0;
    String cmd = argv[i++];  
    String dest = argv[argv.length-1];
    //
    // If the user has specified multiple source files, then
    // the destination has to be a directory
    //
    if (argv.length > 3) {
      Path dst = new Path(dest);
      FileSystem dstFs = dst.getFileSystem(getConf());
      if (!dstFs.isDirectory(dst)) {
        throw new IOException("When moving multiple files, " 
                              + "destination " + dest + " should be a directory.");
      }
    }
    //
    // for each source file, issue the rename
    //
    for (; i < argv.length - 1; i++) {
      try {
        //
        // issue the rename to the fs
        //
        rename(argv[i], dest);
      } catch (RemoteException e) {
        LOG.debug("Error renaming " + argv[i], e);
        //
        // This is a error returned by hadoop server. Print
        // out the first line of the error mesage.
        //
        exitCode = -1;
        try {
          String[] content;
          content = e.getLocalizedMessage().split("\n");
          System.err.println(cmd.substring(1) + ": " + content[0]);
        } catch (Exception ex) {
          System.err.println(cmd.substring(1) + ": " +
                             ex.getLocalizedMessage());
        }
      } catch (IOException e) {
        LOG.debug("Error renaming " + argv[i], e);
        //
        // IO exception encountered locally.
        // 
        exitCode = -1;
        displayError(cmd, e);
      }
    }
    return exitCode;
  }

  /**
   * Copy files that match the file pattern <i>srcf</i>
   * to a destination file.
   * When copying mutiple files, the destination must be a directory. 
   * Otherwise, IOException is thrown.
   * @param srcf a file pattern specifying source files
   * @param dstf a destination local file/directory 
   * @throws IOException  
   * @see org.apache.hadoop.fs.FileSystem#globStatus(Path)
   */
  void copy(String srcf, String dstf, Configuration conf) throws IOException {
    Path srcPath = new Path(srcf);
    FileSystem srcFs = srcPath.getFileSystem(getConf());
    Path dstPath = new Path(dstf);
    FileSystem dstFs = dstPath.getFileSystem(getConf());
    Path [] srcs = FileUtil.stat2Paths(srcFs.globStatus(srcPath), srcPath);
    if (srcs.length > 1 && !dstFs.isDirectory(dstPath)) {
      throw new IOException("When copying multiple files, " 
                            + "destination should be a directory.");
    }
    for(int i=0; i<srcs.length; i++) {
      FileUtil.copy(srcFs, srcs[i], dstFs, dstPath, false, conf);
    }
  }

  /**
   * Copy file(s) to a destination file. Multiple source
   * files can be specified. The destination is the last element of
   * the argvp[] array.
   * If multiple source files are specified, then the destination 
   * must be a directory. Otherwise, IOException is thrown.
   * @exception: IOException  
   */
  private int copy(String argv[], Configuration conf) throws IOException {
    int i = 0;
    int exitCode = 0;
    String cmd = argv[i++];  
    String dest = argv[argv.length-1];
    //
    // If the user has specified multiple source files, then
    // the destination has to be a directory
    //
    if (argv.length > 3) {
      Path dst = new Path(dest);
      FileSystem pFS = dst.getFileSystem(conf);
      if (!pFS.isDirectory(dst)) {
        throw new IOException("When copying multiple files, " 
                              + "destination " + dest + " should be a directory.");
      }
    }
    //
    // for each source file, issue the copy
    //
    for (; i < argv.length - 1; i++) {
      try {
        //
        // issue the copy to the fs
        //
        copy(argv[i], dest, conf);
      } catch (IOException e) {
        LOG.debug("Error copying " + argv[i], e);
        exitCode = -1;
        displayError(cmd, e);
      }
    }
    return exitCode;
  }

  /**
   * Returns the Trash object associated with this shell.
   */
  public Path getCurrentTrashDir() throws IOException {
    return getTrash().getCurrentTrashDir();
  }

  /**
   * Return an abbreviated English-language desc of the byte length
   * @deprecated Consider using {@link org.apache.hadoop.util.StringUtils#byteDesc} instead.
   */
  @Deprecated
  public static String byteDesc(long len) {
    return StringUtils.byteDesc(len);
  }

  /**
   * @deprecated Consider using {@link org.apache.hadoop.util.StringUtils#limitDecimalTo2} instead.
   */
  @Deprecated
  public static synchronized String limitDecimalTo2(double d) {
    return StringUtils.limitDecimalTo2(d);
  }

  private void printHelp(String cmd) {
    String summary = "hadoop fs is the command to execute fs commands. " +
      "The full syntax is: \n\n" +
      "hadoop fs [-fs <local | file system URI>] [-conf <configuration file>]\n\t" +
      "[-D <property=value>]\n\t" +
      "[-mv <src> <dst>] [-cp <src> <dst>]\n\t" + 
      "[-put <localsrc> ... <dst>] [-copyFromLocal <localsrc> ... <dst>]\n\t" +
      "[-moveFromLocal <localsrc> ... <dst>] [" + 
      GET_SHORT_USAGE + "\n\t" +
      "[" + COPYTOLOCAL_SHORT_USAGE + "] [-moveToLocal <src> <localdst>]\n\t" +
      "[-report]";

    String conf ="-conf <configuration file>:  Specify an application configuration file.";
 
    String D = "-D <property=value>:  Use value for given property.";
  
    String fs = "-fs [local | <file system URI>]: \tSpecify the file system to use.\n" + 
      "\t\tIf not specified, the current configuration is used, \n" +
      "\t\ttaken from the following, in increasing precedence: \n" + 
      "\t\t\tcore-default.xml inside the hadoop jar file \n" +
      "\t\t\tcore-site.xml in $HADOOP_CONF_DIR \n" +
      "\t\t'local' means use the local file system as your DFS. \n" +
      "\t\t<file system URI> specifies a particular file system to \n" +
      "\t\tcontact. This argument is optional but if used must appear\n" +
      "\t\tappear first on the command line.  Exactly one additional\n" +
      "\t\targument must be specified. \n";

    String mv = "-mv <src> <dst>:   Move files that match the specified file pattern <src>\n" +
      "\t\tto a destination <dst>.  When moving multiple files, the \n" +
      "\t\tdestination must be a directory. \n";

    String cp = "-cp <src> <dst>:   Copy files that match the file pattern <src> to a \n" +
      "\t\tdestination.  When copying multiple files, the destination\n" +
      "\t\tmust be a directory. \n";

    String put = "-put <localsrc> ... <dst>: \tCopy files " + 
    "from the local file system \n\t\tinto fs. \n";

    String copyFromLocal = "-copyFromLocal <localsrc> ... <dst>:" +
    " Identical to the -put command.\n";

    String moveFromLocal = "-moveFromLocal <localsrc> ... <dst>:" +
    " Same as -put, except that the source is\n\t\tdeleted after it's copied.\n"; 

    String get = GET_SHORT_USAGE
      + ":  Copy files that match the file pattern <src> \n" +
      "\t\tto the local name.  <src> is kept.  When copying mutiple, \n" +
      "\t\tfiles, the destination must be a directory. \n";

    String copyToLocal = COPYTOLOCAL_SHORT_USAGE
                         + ":  Identical to the -get command.\n";

    String moveToLocal = "-moveToLocal <src> <localdst>:  Not implemented yet \n";
        
    String help = "-help [cmd]: \tDisplays help for given command or all commands if none\n" +
      "\t\tis specified.\n";

    Command instance = commandFactory.getInstance("-" + cmd);
    if (instance != null) {
      printHelp(instance);
    } else if ("fs".equals(cmd)) {
      System.out.println(fs);
    } else if ("conf".equals(cmd)) {
      System.out.println(conf);
    } else if ("D".equals(cmd)) {
      System.out.println(D);
    } else if ("mv".equals(cmd)) {
      System.out.println(mv);
    } else if ("cp".equals(cmd)) {
      System.out.println(cp);
    } else if ("put".equals(cmd)) {
      System.out.println(put);
    } else if ("copyFromLocal".equals(cmd)) {
      System.out.println(copyFromLocal);
    } else if ("moveFromLocal".equals(cmd)) {
      System.out.println(moveFromLocal);
    } else if ("get".equals(cmd)) {
      System.out.println(get);
    } else if ("copyToLocal".equals(cmd)) {
      System.out.println(copyToLocal);
    } else if ("moveToLocal".equals(cmd)) {
      System.out.println(moveToLocal);
    } else if ("get".equals(cmd)) {
      System.out.println(get);
    } else if ("help".equals(cmd)) {
      System.out.println(help);
    } else {
      System.out.println(summary);
      for (String thisCmdName : commandFactory.getNames()) {
        instance = commandFactory.getInstance(thisCmdName);
        if (!instance.isDeprecated()) {
          System.out.println("\t[" + instance.getUsage() + "]");
        }
      }
      System.out.println("\t[-help [cmd]]\n");
      
      System.out.println(fs);
      System.out.println(mv);
      System.out.println(cp);
      System.out.println(put);
      System.out.println(copyFromLocal);
      System.out.println(moveFromLocal);
      System.out.println(get);
      System.out.println(copyToLocal);
      System.out.println(moveToLocal);

      for (String thisCmdName : commandFactory.getNames()) {
        instance = commandFactory.getInstance(thisCmdName);
        if (!instance.isDeprecated()) {
          printHelp(instance);
        }
      }
      System.out.println(help);
    }        
  }

  // TODO: will eventually auto-wrap the text, but this matches the expected
  // output for the hdfs tests...
  private void printHelp(Command instance) {
    boolean firstLine = true;
    for (String line : instance.getDescription().split("\n")) {
      String prefix;
      if (firstLine) {
        prefix = instance.getUsage() + ":\t";
        firstLine = false;
      } else {
        prefix = "\t\t";
      }
      System.out.println(prefix + line);
    }    
  }
  
  /**
   * Displays format of commands.
   * 
   */
  private void printUsage(String cmd) {
    String prefix = "Usage: java " + FsShell.class.getSimpleName();

    Command instance = commandFactory.getInstance(cmd);
    if (instance != null) {
      System.err.println(prefix + " [" + instance.getUsage() + "]");
    } else if ("-fs".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [-fs <local | file system URI>]");
    } else if ("-conf".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [-conf <configuration file>]");
    } else if ("-D".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [-D <[property=value>]");
    } else if ("-mv".equals(cmd) || "-cp".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [" + cmd + " <src> <dst>]");
    } else if ("-put".equals(cmd) || "-copyFromLocal".equals(cmd) ||
               "-moveFromLocal".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [" + cmd + " <localsrc> ... <dst>]");
    } else if ("-get".equals(cmd)) {
      System.err.println("Usage: java FsShell [" + GET_SHORT_USAGE + "]"); 
    } else if ("-copyToLocal".equals(cmd)) {
      System.err.println("Usage: java FsShell [" + COPYTOLOCAL_SHORT_USAGE+ "]"); 
    } else if ("-moveToLocal".equals(cmd)) {
      System.err.println("Usage: java FsShell" + 
                         " [" + cmd + " [-crc] <src> <localdst>]");
    } else {
      System.err.println("Usage: java FsShell");
      System.err.println("           [-mv <src> <dst>]");
      System.err.println("           [-cp <src> <dst>]");
      System.err.println("           [-put <localsrc> ... <dst>]");
      System.err.println("           [-copyFromLocal <localsrc> ... <dst>]");
      System.err.println("           [-moveFromLocal <localsrc> ... <dst>]");
      System.err.println("           [" + GET_SHORT_USAGE + "]");
      System.err.println("           [" + COPYTOLOCAL_SHORT_USAGE + "]");
      System.err.println("           [-moveToLocal [-crc] <src> <localdst>]");
      for (String name : commandFactory.getNames()) {
        instance = commandFactory.getInstance(name);
        if (!instance.isDeprecated()) {
          System.err.println("           [" + instance.getUsage() + "]");
        }
      }
      System.err.println("           [-help [cmd]]");
      System.err.println();
      ToolRunner.printGenericCommandUsage(System.err);
    }
  }

  /**
   * run
   */
  public int run(String argv[]) throws Exception {
    // TODO: This isn't the best place, but this class is being abused with
    // subclasses which of course override this method.  There really needs
    // to be a better base class for all commands
    commandFactory.setConf(getConf());
    commandFactory.registerCommands(FsCommand.class);
    
    if (argv.length < 1) {
      printUsage(""); 
      return -1;
    }

    int exitCode = -1;
    int i = 0;
    String cmd = argv[i++];
    //
    // verify that we have enough command line parameters
    //
    if ("-put".equals(cmd) ||
        "-copyFromLocal".equals(cmd) || "-moveFromLocal".equals(cmd)) {
      if (argv.length < 3) {
        printUsage(cmd);
        return exitCode;
      }
    } else if ("-get".equals(cmd) || 
               "-copyToLocal".equals(cmd) || "-moveToLocal".equals(cmd)) {
      if (argv.length < 3) {
        printUsage(cmd);
        return exitCode;
      }
    } else if ("-mv".equals(cmd) || "-cp".equals(cmd)) {
      if (argv.length < 3) {
        printUsage(cmd);
        return exitCode;
      }
    }
    // initialize FsShell
    try {
      init();
    } catch (RPC.VersionMismatch v) {
      LOG.debug("Version mismatch", v);
      System.err.println("Version Mismatch between client and server" +
                         "... command aborted.");
      return exitCode;
    } catch (IOException e) {
      LOG.debug("Error", e);
      System.err.println("Bad connection to FS. Command aborted. Exception: " +
          e.getLocalizedMessage());
      return exitCode;
    }

    exitCode = 0;
    try {
      Command instance = commandFactory.getInstance(cmd);
      if (instance != null) {
        try {
          exitCode = instance.run(Arrays.copyOfRange(argv, i, argv.length));
        } catch (Exception e) {
          exitCode = -1;
          LOG.debug("Error", e);
          instance.displayError(e);
          if (e instanceof IllegalArgumentException) {
            printUsage(cmd);
          }
        }
      } else if ("-put".equals(cmd) || "-copyFromLocal".equals(cmd)) {
        Path[] srcs = new Path[argv.length-2];
        for (int j=0 ; i < argv.length-1 ;) 
          srcs[j++] = new Path(argv[i++]);
        copyFromLocal(srcs, argv[i++]);
      } else if ("-moveFromLocal".equals(cmd)) {
        Path[] srcs = new Path[argv.length-2];
        for (int j=0 ; i < argv.length-1 ;) 
          srcs[j++] = new Path(argv[i++]);
        moveFromLocal(srcs, argv[i++]);
      } else if ("-get".equals(cmd) || "-copyToLocal".equals(cmd)) {
        copyToLocal(argv, i);
      } else if ("-moveToLocal".equals(cmd)) {
        moveToLocal(argv[i++], new Path(argv[i++]));
      } else if ("-mv".equals(cmd)) {
        exitCode = rename(argv, getConf());
      } else if ("-cp".equals(cmd)) {
        exitCode = copy(argv, getConf());
      } else if ("-help".equals(cmd)) {
        if (i < argv.length) {
          printHelp(argv[i]);
        } else {
          printHelp("");
        }
      } else {
        exitCode = -1;
        System.err.println(cmd.substring(1) + ": Unknown command");
        printUsage("");
      }
    } catch (IllegalArgumentException arge) {
      LOG.debug("Error", arge);
      exitCode = -1;
      System.err.println(cmd.substring(1) + ": " + arge.getLocalizedMessage());
      printUsage(cmd);
    } catch (Exception re) {
      LOG.debug("Error", re);
      exitCode = -1;
      displayError(cmd, re);
    } finally {
    }
    return exitCode;
  }

  // TODO: this is a quick workaround to accelerate the integration of
  // redesigned commands.  this will be removed this once all commands are
  // converted.  this change will avoid having to change the hdfs tests
  // every time a command is converted to use path-based exceptions
  private static Pattern[] fnfPatterns = {
    Pattern.compile("File (.*) does not exist\\."),
    Pattern.compile("File does not exist: (.*)"),
    Pattern.compile("`(.*)': specified destination directory doest not exist")
  };
  private void displayError(String cmd, Exception e) {
    String message = e.getLocalizedMessage().split("\n")[0];
    for (Pattern pattern : fnfPatterns) {
      Matcher matcher = pattern.matcher(message);
      if (matcher.matches()) {
        message = new PathNotFoundException(matcher.group(1)).getMessage();
        break;
      }
    }
    System.err.println(cmd.substring(1) + ": " + message);  
  }
  
  public void close() throws IOException {
    if (fs != null) {
      fs.close();
      fs = null;
    }
  }

  /**
   * main() has some simple utility methods
   */
  public static void main(String argv[]) throws Exception {
    FsShell shell = new FsShell();
    int res;
    try {
      res = ToolRunner.run(shell, argv);
    } finally {
      shell.close();
    }
    System.exit(res);
  }
}
