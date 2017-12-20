/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package thredds.server.catalog;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import thredds.client.catalog.Catalog;
import thredds.client.catalog.Dataset;
import thredds.client.catalog.Service;
import thredds.client.catalog.builder.CatalogBuilder;
import thredds.client.catalog.tools.CatalogXmlWriter;
import thredds.core.StandardService;
import thredds.inventory.MFile;
import ucar.nc2.util.AliasTranslator;
import ucar.unidata.util.test.TestDir;
import ucar.unidata.util.test.TestFileDirUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class TestDatasetScanFilter {
  @ClassRule public static final TemporaryFolder tempFolder = new TemporaryFolder();

  private static File tmpTestDataDir;
  private static MFile tmpTestDataCrDs;
  private static List<String> dataFiles_FullPathNames;
  private static List<String> allFiles_FullPathNames;

  @BeforeClass
  public static void setupTestDataDir() throws IOException {
    tmpTestDataDir = tempFolder.newFolder("TestMFileFilter");
    System.out.printf("tmpLocalRootDataDir = %s%n", tmpTestDataDir);

    AliasTranslator.addAlias("${tmpDir}", tmpTestDataDir.getPath());
    AliasTranslator.addAlias("${cdmUnitTest}", TestDir.cdmUnitTestDir);

    File tmpTestDir = TestFileDirUtils.addDirectory(tmpTestDataDir, "testDatafilesInDateTimeNestedDirs");
    assertNotNull(tmpTestDir);
    assertTrue(tmpTestDir.exists());
    assertTrue(tmpTestDir.canRead());
    assertTrue(tmpTestDir.canWrite());

    File profilesDir = TestFileDirUtils.addDirectory(tmpTestDir, "profiles");
    File firstDayDir = TestFileDirUtils.addDirectory(profilesDir, "20131106");

    TestFileDirUtils.addFile(firstDayDir, "PROFILER_wind_06min_20131106_2341.nc");
    TestFileDirUtils.addFile(firstDayDir, "PROFILER_wind_06min_20131106_2348.nc");
    TestFileDirUtils.addFile(firstDayDir, "PROFILER_wind_06min_20131106_2354.nc");

    File secondDayDir = TestFileDirUtils.addDirectory(profilesDir, "20131107");
    TestFileDirUtils.addFile(secondDayDir, "PROFILER_wind_06min_20131107_0001.nc");
    TestFileDirUtils.addFile(secondDayDir, "PROFILER_wind_06min_20131107_0008.nc");
    TestFileDirUtils.addFile(secondDayDir, "PROFILER_wind_06min_20131107_0014.nc");
    TestFileDirUtils.addFile(secondDayDir, "PROFILER_wind_06min_20131108_0016.nc");

    StandardService ss = StandardService.resolver;
    Service latest = new Service(ss.getType().toString(), ss.getBase(), ss.getType().toString(), null, null, null, null);
    StandardService ss2 = StandardService.httpServer;
    Service httpServer = new Service(ss2.getType().toString(), ss2.getBase(), ss2.getType().toString(), null, null, null, null);

    DatasetScan.setSpecialServices(latest, httpServer);
  }

  /* public void createEtaDirWithCvsAndDotGitDirs( File targetDir) {

    tmpTestDataCrDs = createMFile( targetDir.getPath(), targetDir.getName());

    List<String> dirNamesToIgnore = new ArrayList<String>();
    dirNamesToIgnore.add("CVS");
    dirNamesToIgnore.add(".git");

    List<String> dataFileNames = new ArrayList<String>();
    dataFileNames.add("2004050300_eta_211.nc");
    dataFileNames.add("2004050312_eta_211.nc");
    dataFileNames.add("2004050400_eta_211.nc");
    dataFileNames.add("2004050412_eta_211.nc");

    for ( String dirName : dirNamesToIgnore )
      TestFileDirUtils.addDirectory( targetDir, dirName);

    for ( String fileName : dataFileNames )
      TestFileDirUtils.addFile( targetDir, fileName );

    allFiles_FullPathNames = new ArrayList<String>();
    dataFiles_FullPathNames = new ArrayList<String>();

    for ( String fileName : dirNamesToIgnore )
      allFiles_FullPathNames.add( String.format( "%s/%s", tmpTestDataCrDs.getPath(), fileName));

    for ( String fileName : dataFileNames ) {
      String path = String.format("%s/%s", tmpTestDataCrDs.getPath(), fileName);
      allFiles_FullPathNames.add( path);
      dataFiles_FullPathNames.add( path);
    }

  } */

  @Test
  public void testWildcardFilter() throws IOException {
    ConfigCatalog cat = TestConfigCatalogBuilder.getFromResource("thredds/server/catalog/TestDatasetScan.xml");

    Dataset ds = cat.findDatasetByID("testGridScan");
    assert ds != null;
    assert (ds instanceof DatasetScan);
    DatasetScan dss = (DatasetScan) ds;
    String serviceName = dss.getServiceNameDefault();
    assert serviceName.equals("all");

    DatasetScanConfig config = dss.getConfig();
    System.out.printf("%s%n", config);

    Catalog scanCat = dss.makeCatalogForDirectory("testGridScan", cat.getBaseURI()).makeCatalog();
    assert scanCat != null;

    CatalogXmlWriter writer = new CatalogXmlWriter();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    Dataset root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 1;

    scanCat = dss.makeCatalogForDirectory("testGridScan/testDatafilesInDateTimeNestedDirs/profiles", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 2;

    scanCat = dss.makeCatalogForDirectory("testGridScan/testDatafilesInDateTimeNestedDirs/profiles/20131106", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    Assert.assertEquals(3, root.getDatasets().size());

    scanCat = dss.makeCatalogForDirectory("testGridScan/testDatafilesInDateTimeNestedDirs/profiles/20131107", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 4;
  }

  @Test
  public void testRegexpFilter() throws IOException {
    ConfigCatalog cat = TestConfigCatalogBuilder.getFromResource("thredds/server/catalog/TestDatasetScan.xml");

    Dataset ds = cat.findDatasetByID("testGridScanReg");
    assert ds != null;
    assert (ds instanceof DatasetScan);
    DatasetScan dss = (DatasetScan) ds;
    String serviceName = dss.getServiceNameDefault();
    assert serviceName.equals("all");

    Catalog scanCat = dss.makeCatalogForDirectory("testGridScanReg", cat.getBaseURI()).makeCatalog();
    assert scanCat != null;

    CatalogXmlWriter writer = new CatalogXmlWriter();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    Dataset root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 1;

    scanCat = dss.makeCatalogForDirectory("testGridScanReg/testDatafilesInDateTimeNestedDirs/profiles", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 2;

    scanCat = dss.makeCatalogForDirectory("testGridScanReg/testDatafilesInDateTimeNestedDirs/profiles/20131106", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    Assert.assertEquals(3, root.getDatasets().size());

    scanCat = dss.makeCatalogForDirectory("testGridScanReg/testDatafilesInDateTimeNestedDirs/profiles/20131107", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 3;
  }

  @Test
  public void testExcludeDir() throws IOException {
    ConfigCatalog cat = TestConfigCatalogBuilder.getFromResource("thredds/server/catalog/TestDatasetScan.xml");
    CatalogXmlWriter writer = new CatalogXmlWriter();
    System.out.printf("%n%s%n", writer.writeXML(cat));

    Dataset ds = cat.findDatasetByID("testExclude");
    assert ds != null;
    assert (ds instanceof DatasetScan);
    DatasetScan dss = (DatasetScan) ds;
    String serviceName = dss.getServiceNameDefault();
    assert serviceName.equals("all");

    Catalog scanCat = dss.makeCatalogForDirectory("testExclude", cat.getBaseURI()).makeCatalog();
    assert scanCat != null;

    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    Dataset root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 1;

    scanCat = dss.makeCatalogForDirectory("testExclude/testDatafilesInDateTimeNestedDirs/profiles", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    assert root.getDatasets().size() == 1;

    scanCat = dss.makeCatalogForDirectory("testExclude/testDatafilesInDateTimeNestedDirs/profiles/20131106", cat.getBaseURI()).makeCatalog();
    System.out.printf("%n%s%n", writer.writeXML(scanCat));
    assert scanCat.getDatasets().size() == 1;
    root = scanCat.getDatasets().get(0);
    Assert.assertEquals(3, root.getDatasets().size());
  }

  @Test
  public void testExcludeDirFails() throws IOException {
    ConfigCatalog cat = TestConfigCatalogBuilder.getFromResource("thredds/server/catalog/TestDatasetScan.xml");;
    assert cat != null;

    Dataset ds = cat.findDatasetByID("testExclude");
    assert ds != null;
    assert (ds instanceof DatasetScan);
    DatasetScan dss = (DatasetScan) ds;
    CatalogBuilder catb = dss.makeCatalogForDirectory("testGridScanReg/testDatafilesInDateTimeNestedDirs/profiles/20131107", cat.getBaseURI());
    assert catb == null;
  }

  @Test
  public void testRegexp() throws IOException {
    testOne("PROFILER_wind_06min_2013110[67]_[0-9]{4}.nc", "PROFILER_wind_06min_20131107_0001.nc", true);
    testOne("PROFILER_wind_06min_2013110[67]_[0-9]{4}\\.nc", "PROFILER_wind_06min_20131107_0001.nc", true);
    testOne("PROFILER_wind_06min_2013110[67]_[0-9]{4}\\\\.nc", "PROFILER_wind_06min_20131107_0001.nc", false);
  }

  public static void testOne(String ps, String match, boolean expect) {
    Pattern pattern = Pattern.compile(ps);
    Matcher matcher = pattern.matcher(match);
    assertEquals("match " + ps + " against: " + match, expect, matcher.matches() );
  }
}
