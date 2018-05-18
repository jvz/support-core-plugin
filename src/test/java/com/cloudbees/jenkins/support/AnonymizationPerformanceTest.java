/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.jenkins.support;

import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.FileContent;
import com.cloudbees.jenkins.support.filter.ContentFilters;
import com.github.javafaker.Faker;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.security.Permission;
import java.io.File;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

@Ignore("Intended to be run manually")
public class AnonymizationPerformanceTest {
    
    private static final int REPETITIONS = 10;
    private static final int ITEMS_TO_CREATE = 50;
    private static final int NODES_TO_CREATE = 5;
    private static final int LINES_OF_TEXT_TO_ADD = 100_000;
    
    @Rule
    public JenkinsRule r = new JenkinsRule();
    
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    
    @Before
    public void setup() throws Exception {
        // Create some items that will be anonymized.
        for (int i = 0; i < ITEMS_TO_CREATE; i++) {
            MockFolder folder = r.createFolder("TestFolder" + i);
            folder.createProject(FreeStyleProject.class, "TestJob" + i);
        }
        // Create some nodes, each will have a computer and two labels.
        for (int i = 0; i < NODES_TO_CREATE; i++) {
            r.createSlave("Node" + i + ",NodeLabel" + i, null);
        }
        // Create a large file that will need to be scanned during anonymization.
        Faker faker = new Faker(Locale.ENGLISH);
        File testFile = new File(r.jenkins.getRootDir(), "TestFile.txt");
        try (Writer writer = Files.newBufferedWriter(testFile.toPath())) {
            for (int i = 0; i < LINES_OF_TEXT_TO_ADD; i++) {
                writer.write(faker.lorem().sentence() + System.lineSeparator());
            }
        }
    }
    
    @Test
    public void generateBundle() throws Exception {
        File file = temp.newFile();
        assertThat(ExtensionList.lookup(Component.class).get(TestFileComponent.class), notNullValue());
        
        ContentFilters.get().setEnabled(false);
        Instant start = Instant.now();
        for (int i = 0; i < REPETITIONS; i++) {
            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                SupportPlugin.writeBundle(os);
            }
            Files.delete(file.toPath());
        }
        System.out.println("Average generation time for unanonymized bundle: " + Duration.between(start, Instant.now()).dividedBy(REPETITIONS));
        
        ContentFilters.get().setEnabled(true);
        start = Instant.now();
        for (int i = 0; i < REPETITIONS; i++) {
            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                SupportPlugin.writeBundle(os);
            }
            Files.delete(file.toPath());
        }
        System.out.println("Average generation time for anonymized bundle: " + Duration.between(start, Instant.now()).dividedBy(REPETITIONS));
    }
    
    @TestExtension
    public static class TestFileComponent extends Component {
        @Override
        public Set<Permission> getRequiredPermissions() {
            return Collections.singleton(Jenkins.READ);
        }

        @Override
        public String getDisplayName() {
            return TestFileComponent.class.getName();
        }

        @Override
        public void addContents(Container container) {
            container.add(new FileContent("TestFile.txt", new File(Jenkins.get().getRootDir(), "TestFile.txt")));
        }
    }
}
