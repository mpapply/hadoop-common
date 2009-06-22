package org.apache.hadoop.dfs;

import junit.framework.TestCase;
import java.io.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;


/**
 * This class tests that the DFS command mkdirs cannot create subdirectories
 * from a file when passed an illegal path.  HADOOP-281.
 * @author Wendy Chien
 */
public class TestDFSMkdirs extends TestCase {

  private void writeFile(FileSystem fileSys, Path name) throws IOException {
    DataOutputStream stm = fileSys.create(name);
    stm.writeBytes("wchien");
    stm.close();
  }
  
  /**
   * Tests mkdirs can create a directory that does not exist and will
   * not create a subdirectory off a file.
   */
  public void testDFSMkdirs() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = new MiniDFSCluster(65312, conf, false);
    FileSystem fileSys = cluster.getFileSystem();
    try {
    	// First create a new directory with mkdirs
    	Path myPath = new Path("/test/mkdirs");
    	assertTrue(fileSys.mkdirs(myPath));
    	assertTrue(fileSys.exists(myPath));
    	
    	// Second, create a file in that directory.
    	Path myFile = new Path("/test/mkdirs/myFile");
    	writeFile(fileSys, myFile);
   
    	// Third, use mkdir to create a subdirectory off of that file,
    	// and check that it fails.
    	Path myIllegalPath = new Path("/test/mkdirs/myFile/subdir");
    	assertFalse(fileSys.mkdirs(myIllegalPath));
    	assertFalse(fileSys.exists(myIllegalPath));
    	fileSys.delete(myFile);
    	
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }
}