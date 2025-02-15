// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.zookeeper;

import com.yahoo.component.Version;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provisioning.FlavorsConfig;
import com.yahoo.io.IOUtils;
import com.yahoo.path.Path;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.server.filedistribution.MockFileManager;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.yahoo.config.provision.serialization.AllocatedHostsSerializer.toJson;
import static com.yahoo.vespa.config.server.zookeeper.ZKApplication.META_ZK_PATH;
import static com.yahoo.vespa.config.server.zookeeper.ZKApplication.USERAPP_ZK_SUBPATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZKApplicationPackageTest {

    private static final FilenameFilter acceptsAllFileNameFilter = (dir, name) -> true;
    private static final String APP = "src/test/apps/zkapp";
    private static final String TEST_FLAVOR_NAME = "test-flavor";
    private static final Optional<Flavor> TEST_FLAVOR = new MockNodeFlavors().getFlavor(TEST_FLAVOR_NAME);
    private static final AllocatedHosts ALLOCATED_HOSTS;
    private static final String dockerImage = "docker.foo.com:4443/vespa/bar";

    static {
        var nodeResources = TEST_FLAVOR.orElseThrow(() -> new IllegalArgumentException("node resource found")).resources();
        ALLOCATED_HOSTS = AllocatedHosts.withHosts(
                Set.of(new HostSpec("foo.yahoo.com",
                                    nodeResources,
                                    nodeResources,
                                    nodeResources,
                                    ClusterMembership.from("container/test/0/0", Version.fromString("6.73.1"),
                                                           Optional.of(DockerImage.fromString(dockerImage))),
                                    Optional.of(Version.fromString("6.0.1")), Optional.empty(),
                                    Optional.of(DockerImage.fromString(dockerImage)))));
    }

    private Curator curator;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Before
    public void setup() {
        curator = new MockCurator();
    }

    @Test
    public void testBasicZKFeed() throws IOException {
        feed(curator, new File(APP));
        ZKApplicationPackage zkApp = new ZKApplicationPackage(new MockFileManager(), curator, Path.fromString("/0"));
        assertTrue(Pattern.compile(".*<slobroks>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getServices())).matches());
        assertTrue(Pattern.compile(".*<alias>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getHosts())).matches());
        assertTrue(Pattern.compile(".*<slobroks>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getFile(Path.fromString("services.xml")).createReader())).matches());
        DeployState deployState = new DeployState.Builder().applicationPackage(zkApp).build();
        assertEquals(deployState.getSchemas().size(), 5);
        assertEquals(zkApp.getSchemas().size(), 5);
        assertEquals(IOUtils.readAll(zkApp.getRankingExpression("foo.expression")), "foo()+1\n");
        assertEquals(zkApp.getFiles(Path.fromString(""), "xml").size(), 3);
        assertEquals(zkApp.getFileReference(Path.fromString("components/file.txt")).getAbsolutePath(), "/home/vespa/test/file.txt");
        try (Reader foo = zkApp.getFile(Path.fromString("files/foo.json")).createReader()) {
            assertEquals(IOUtils.readAll(foo), "foo : foo\n");
        }
        try (Reader bar = zkApp.getFile(Path.fromString("files/sub/bar.json")).createReader()) {
            assertEquals(IOUtils.readAll(bar), "bar : bar\n");
        }
        assertTrue(zkApp.getFile(Path.createRoot()).exists());
        assertTrue(zkApp.getFile(Path.createRoot()).isDirectory());
        Version goodVersion = new Version(3, 0, 0);
        assertTrue(zkApp.getFileRegistries().containsKey(goodVersion));
        assertFalse(zkApp.getFileRegistries().containsKey(new Version(0, 0, 0)));
        AllocatedHosts readInfo = zkApp.getAllocatedHosts().get();
        assertEquals(Utf8.toString(toJson(ALLOCATED_HOSTS)), Utf8.toString(toJson(readInfo)));
        assertEquals(TEST_FLAVOR.get().resources(), readInfo.getHosts().iterator().next().advertisedResources());
        assertEquals("6.0.1", readInfo.getHosts().iterator().next().version().get().toString());
        assertEquals(dockerImage, readInfo.getHosts().iterator().next().dockerImageRepo().get().asString());
        assertTrue(zkApp.getDeployment().isPresent());
        assertFalse(zkApp.getDeploymentSpec().isEmpty());
    }

    private void feed(com.yahoo.vespa.curator.Curator zk, File dirToFeed) throws IOException {
        assertTrue(dirToFeed.isDirectory());
        Path sessionPath = Path.fromString("/0");
        feedZooKeeper(zk, dirToFeed, sessionPath.append(USERAPP_ZK_SUBPATH), null, true);
        String metaData = "{\"deploy\":{\"user\":\"foo\",\"from\":\"bar\",\"timestamp\":1},\"application\":{\"id\":\"foo:foo:default\",\"checksum\":\"abc\",\"generation\":4,\"previousActiveGeneration\":3}}";
        zk.set(sessionPath.append(META_ZK_PATH), Utf8.toBytes(metaData));
        zk.set(sessionPath.append(ZKApplicationPackage.fileRegistryNode).append("/3.0.0"), Utf8.toBytes("dummyfiles"));
        zk.set(sessionPath.append(ZKApplicationPackage.allocatedHostsNode), toJson(ALLOCATED_HOSTS));
    }

    private static class MockNodeFlavors extends NodeFlavors{

        MockNodeFlavors() { super(flavorsConfig()); }

        private static FlavorsConfig flavorsConfig() {
            return new FlavorsConfig(new FlavorsConfig.Builder()
                            .flavor(new FlavorsConfig.Flavor.Builder().name(TEST_FLAVOR_NAME))
            );
        }
    }

    /**
     * Takes for instance the dir /app  and puts the contents into the given ZK path. Ignores files starting with dot.
     *
     * @param dir            directory which holds the summary class part files
     * @param path           zookeeper path
     * @param filenameFilter A FilenameFilter which decides which files in dir are fed to zookeeper
     * @param recurse        recurse subdirectories
     */
    static void feedZooKeeper(com.yahoo.vespa.curator.Curator zk, File dir, Path path, FilenameFilter filenameFilter, boolean recurse) {
        try {
            if (filenameFilter == null) {
                filenameFilter = acceptsAllFileNameFilter;
            }
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException(dir + " is not a directory");
            }
            for (File file : listFiles(dir, filenameFilter)) {
                if (file.getName().startsWith(".")) continue; //.svn , .git ...
                Path filePath = path.append(file.getName());
                if (file.isFile()) {
                    byte[] contents = IOUtils.readFileBytes(file);
                    zk.set(filePath, contents);
                } else if (recurse && file.isDirectory()) {
                    zk.create(filePath);
                    feedZooKeeper(zk, file, filePath, filenameFilter, recurse);
                }
            }
        }
        catch (IOException e) {
            throw new RuntimeException("Exception feeding ZooKeeper at path " + path, e);
        }
    }

    /**
     * Same as normal listFiles, but use the filter only for normal files
     *
     * @param dir    directory to list files in
     * @param filter A FilenameFilter which decides which files in dir are listed
     * @return an array of Files
     */
    protected static File[] listFiles(File dir, FilenameFilter filter) {
        File[] rawList = dir.listFiles();
        List<File> ret = new ArrayList<>();
        if (rawList != null) {
            for (File f : rawList) {
                if (f.isDirectory()) {
                    ret.add(f);
                } else {
                    if (filter.accept(dir, f.getName())) {
                        ret.add(f);
                    }
                }
            }
        }
        return ret.toArray(new File[0]);
    }


}
