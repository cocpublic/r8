// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;

import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.ZipUtils;
import com.android.tools.r8.utils.ZipUtils.OnEntryHandler;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class R8EntryPointTests extends TestBase {

  private static final String MAPPING = "mapping.txt";
  private static final String SEEDS = "seeds.txt";
  private static final Path INPUT_JAR =
      Paths.get(ToolHelper.EXAMPLES_BUILD_DIR, "minification" + FileUtils.JAR_EXTENSION);
  private static final Path PROGUARD_FLAGS =
      Paths.get(ToolHelper.EXAMPLES_DIR, "minification",  "keep-rules.txt");

  private Path testFlags;

  @Before
  public void setup() throws IOException {
    testFlags = temp.newFile("local.flags").toPath();
    FileUtils.writeTextFile(testFlags, ImmutableList.of(
        "-printseeds " + SEEDS,
        "-printmapping " + MAPPING));
  }

  @Test
  public void testRun1Dir() throws Exception {
    Path out = temp.newFolder("outdex").toPath();
    R8.run(getCommand(out));
    Assert.assertTrue(Files.isRegularFile(out.resolve(ToolHelper.DEFAULT_DEX_FILENAME)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testRun1Zip() throws Exception {
    Path out = temp.newFolder("outdex").toPath().resolve("dex.zip");
    R8.run(getCommand(out));
    Assert.assertTrue(Files.isRegularFile(out));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testRun2Dir() throws Exception {
    Path out = temp.newFolder("outdex").toPath();
    ExecutorService executor = Executors.newWorkStealingPool(2);
    try {
      R8.run(getCommand(out), executor);
    } finally {
      executor.shutdown();
    }
    Assert.assertTrue(Files.isRegularFile(out.resolve(ToolHelper.DEFAULT_DEX_FILENAME)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testRun2Zip() throws Exception {
    Path out = temp.newFolder("outdex").toPath().resolve("dex.zip");
    ExecutorService executor = Executors.newWorkStealingPool(2);
    try {
      R8.run(getCommand(out), executor);
    } finally {
      executor.shutdown();
    }
    Assert.assertTrue(Files.isRegularFile(out));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testMainDir() throws IOException, InterruptedException {
    Path out = temp.newFolder("outdex").toPath();
    ProcessResult r8 = ToolHelper.forkR8(Paths.get("."),
        "--lib", ToolHelper.getDefaultAndroidJar().toString(),
        "--output", out.toString(),
        "--pg-conf", PROGUARD_FLAGS.toString(),
        "--pg-conf", testFlags.toString(),
        INPUT_JAR.toString());
    Assert.assertEquals(0, r8.exitCode);
    Assert.assertTrue(Files.isRegularFile(out.resolve(ToolHelper.DEFAULT_DEX_FILENAME)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testMainRelativeDir() throws IOException, InterruptedException {
    temp.newFolder("outdex");
    Path out = Paths.get("outdex");
    Path workingDir = temp.getRoot().toPath();
    ProcessResult r8 = ToolHelper.forkR8(workingDir,
        "--lib", ToolHelper.getDefaultAndroidJar().toAbsolutePath().toString(),
        "--output", out.toString(),
        "--pg-conf", PROGUARD_FLAGS.toAbsolutePath().toString(),
        "--pg-conf", testFlags.toAbsolutePath().toString(),
        INPUT_JAR.toAbsolutePath().toString());
    Assert.assertEquals(0, r8.exitCode);
    Assert.assertTrue(
        Files.isRegularFile(workingDir.resolve(out).resolve(ToolHelper.DEFAULT_DEX_FILENAME)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }


  @Test
  public void testMainZip() throws IOException, InterruptedException {
    Path out = temp.newFolder("outdex").toPath().resolve("dex.zip");
    ProcessResult r8 = ToolHelper.forkR8(Paths.get("."),
        "--lib", ToolHelper.getDefaultAndroidJar().toString(),
        "--output", out.toString(),
        "--pg-conf", PROGUARD_FLAGS.toString(),
        "--pg-conf", testFlags.toString(),
        INPUT_JAR.toString());
    Assert.assertEquals(0, r8.exitCode);
    Assert.assertTrue(Files.isRegularFile(out));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(MAPPING)));
    Assert.assertTrue(Files.isRegularFile(testFlags.getParent().resolve(SEEDS)));
  }

  @Test
  public void testSpecifyClassfile() throws IOException, InterruptedException {
    Path out = temp.newFile("classfile.zip").toPath();
    ProcessResult r8 =
        ToolHelper.forkR8(
            Paths.get("."),
            "--lib",
            ToolHelper.getJava8RuntimeJar().toString(),
            "--classfile",
            "--output",
            out.toString(),
            "--pg-conf",
            PROGUARD_FLAGS.toString(),
            "--pg-conf",
            testFlags.toString(),
            INPUT_JAR.toString());
    Assert.assertEquals(0, r8.exitCode);
  }

  @Test
  public void testSpecifyDex() throws IOException, InterruptedException {
    Path out = temp.newFile("dex.zip").toPath();
    ProcessResult r8 =
        ToolHelper.forkR8(
            Paths.get("."),
            "--lib",
            ToolHelper.getDefaultAndroidJar().toString(),
            "--dex",
            "--output",
            out.toString(),
            "--pg-conf",
            PROGUARD_FLAGS.toString(),
            "--pg-conf",
            testFlags.toString(),
            INPUT_JAR.toString());
    Assert.assertEquals(0, r8.exitCode);
  }

  @Test
  public void testDumpInputs() throws IOException {
    Path out = temp.newFile("dex.zip").toPath();
    Path dump = temp.newFile("dump.zip").toPath();
    ProcessResult r8 =
        ToolHelper.forkR8WithJavaOptions(
            Paths.get("."),
            ImmutableList.of("-Dcom.android.tools.r8.dumpinputtofile=" + dump.toString()),
            "--lib",
            ToolHelper.getDefaultAndroidJar().toString(),
            "--dex",
            "--output",
            out.toString(),
            "--pg-conf",
            PROGUARD_FLAGS.toString(),
            "--pg-conf",
            testFlags.toString(),
            INPUT_JAR.toString());

    List<ZipEntry> entries = new ArrayList<>();
    ZipUtils.iter(
        dump.toString(),
        new OnEntryHandler() {
          @Override
          public void onEntry(ZipEntry entry, InputStream input) throws IOException {
            entries.add(entry);
          }
        });
    Assert.assertTrue(hasEntry(entries, "program.jar"));
    Assert.assertTrue(hasEntry(entries, "library.jar"));
    Assert.assertTrue(hasEntry(entries, "classpath.jar"));
    Assert.assertTrue(hasEntry(entries, "proguard.config"));
    Assert.assertTrue(hasEntry(entries, "r8-version"));
    // When dumping the inputs we throw an error in the program to exit early.
    Assert.assertNotEquals(0, r8.exitCode);
  }

  private boolean hasEntry(Collection<ZipEntry> entries, String name) {
    for (ZipEntry entry : entries) {
      if (entry.getName().equals(name)) {
        return true;
      }
    }

    return false;
  }

  @Test
  public void testSpecifyDexAndClassfileNotAllowed() throws IOException, InterruptedException {
    Path out = temp.newFile("dex.zip").toPath();
    ProcessResult r8 =
        ToolHelper.forkR8(
            Paths.get("."),
            "--lib",
            ToolHelper.getDefaultAndroidJar().toString(),
            "--dex",
            "--classfile",
            "--output",
            out.toString(),
            "--pg-conf",
            PROGUARD_FLAGS.toString(),
            "--pg-conf",
            testFlags.toString(),
            INPUT_JAR.toString());
    Assert.assertEquals(1, r8.exitCode);
    assertThat(
        r8.stderr, containsString("Cannot compile in both --dex and --classfile output mode"));
  }

  private R8Command getCommand(Path out) throws IOException, CompilationFailedException {
    return R8Command.builder()
        .addLibraryFiles(ToolHelper.getDefaultAndroidJar())
        .addProgramFiles(INPUT_JAR)
        .setOutput(out, OutputMode.DexIndexed)
        .addProguardConfigurationFiles(PROGUARD_FLAGS, testFlags)
        .build();
  }
}
