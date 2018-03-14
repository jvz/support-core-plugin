/*
 * The MIT License
 *
 * Copyright (c) 2015 schristou88
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
package com.cloudbees.jenkins.support.util;

import com.cloudbees.jenkins.support.SupportPlugin;
import hudson.Functions;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.model.View;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.randname.RandomNameGenerator;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class Anonymizer {
    private static final Logger LOGGER = Logger.getLogger(Anonymizer.class.getName());
    private static final RandomNameGenerator GENERATOR = new RandomNameGenerator();
    private static final Set<String> SEPARATORS = new CopyOnWriteArraySet<>();
    private static final Map<String, String> ANON_MAP = new ConcurrentHashMap<>();
    private static final Set<String> ORDER = new ConcurrentSkipListSet<>(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
    private static final Map<String, String> DISPLAY = new ConcurrentSkipListMap<>();
    private static final long DISPLAY_REFRESH_INTERVAL = 1000 * 60 * 10; // 10 mins

    private static long LAST_REFRESH;

    private Anonymizer() { }

    static {
        // Full names can have this separator
        SEPARATORS.add(" » ");
        refresh();
    }

    @SuppressWarnings("unchecked")
    public static void refresh() {
        LOGGER.log(Level.FINE, "Refreshing anonymized items");
        Jenkins instance = Jenkins.getInstance();
        load();

        SupportPlugin.AnonymizationSettings settings = SupportPlugin.getInstance().getAnonymizationSettings();
        if (settings.isAnonymizeLabels()) {
            for (Label label : instance.getLabels()) {
                anonymizeName(label.getName(), "label", "", false);
            }
        }

        if (settings.isAnonymizeItems()) {
            for (Item item : instance.getAllItems()) {
                anonymizePath(item.getFullName(), "item", false);
            }
        }

        if (settings.isAnonymizeViews()) {
            for (View view : instance.getViews()) {
                anonymizeName(view.getViewName(), "view", "", false);
            }
        }

        if (settings.isAnonymizeNodes()) {
            for (Node node : instance.getNodes()) {
                anonymizeName(node.getDisplayName(), "node", "", false);
            }
        }

        if (settings.isAnonymizeComputers()) {
            for (Computer computer : instance.getComputers()) {
                anonymizeName(computer.getDisplayName(), "computer", "", false);
            }
        }

        if (settings.isAnonymizeUsers()) {
            for (User user : User.getAll()) {
                anonymizeName(user.getDisplayName(), "user", "", false);
            }
        }

        save();
    }

    public static String anonymize(String input) {
        String anonymized = input;
        for (String key : ORDER) {
            anonymized = anonymized.replaceAll("(?i)\\b(" + key + ")\\b", ANON_MAP.get(key));
        }
        return anonymized;
    }

    public static Map<String, String> getAnonymizedItems() {
        return Collections.unmodifiableMap(ANON_MAP);
    }

    public static Map<String, String> getDisplayItems() {
        if (LAST_REFRESH + DISPLAY_REFRESH_INTERVAL < System.currentTimeMillis()) {
            refresh();
        }
        return DISPLAY;
    }

    private static String anonymizePath(String original, String prefix, boolean save) {
        String[] labels = original.split("/");
        StringBuilder oldPath = new StringBuilder();
        String newPath = "";
        for (int i = 0; i < labels.length; i++) {
            oldPath.append(labels[i]);
            newPath = anonymizeName(oldPath.toString(), prefix, newPath.toString(), save);
            if (i != labels.length - 1) {
                oldPath.append("/");
                newPath += "/";
            }
        }
        if (original.endsWith("/")) {
            newPath += "/";
        }
        return newPath.toString();
    }

    private static String anonymizeName(String original, String prefix, String newPath, boolean save) {
        if (SupportPlugin.getInstance().getExcludedWordsFromAnonymization().contains(original.toLowerCase(Locale.ENGLISH))) {
            return original;
        } else if (!ANON_MAP.containsKey(original)) {
            ORDER.add(original);
            String anonymized = newPath + prefix + "_" + GENERATOR.next();
            ANON_MAP.put(original, anonymized);
            ANON_MAP.put(Functions.escape(original), anonymized);
            DISPLAY.put(original, anonymized);
            for (String separator : SEPARATORS) {
                String replaced = original.replaceAll("/", separator);
                ANON_MAP.put(replaced, anonymized);
                ANON_MAP.put(Functions.escape(replaced), anonymized);
            }

            if (save) {
                save();
            }
        }
        return ANON_MAP.get(original);
    }

    private static XmlFile getConfigXml() {
        return new XmlFile(Jenkins.XSTREAM, new File(Jenkins.get().getRootDir(), "secrets/anonymized-names.xml"));
    }

    @SuppressWarnings("unchecked")
    private static synchronized void load() {
        XmlFile config = getConfigXml();
        if (config.exists()) {
            try {
                ANON_MAP.clear();
                ANON_MAP.putAll((Map<? extends String, ? extends String>) config.read());
            } catch (IOException e) {
//                // Continuing has the potential of overwriting the saved relationships, which will make it extremely
//                // difficult to reverse the anonymization
                throw new IllegalStateException("Could not load anonymized names", e);
            }
        }
    }

    private static synchronized void save() {
        LAST_REFRESH = System.currentTimeMillis();
        XmlFile config = getConfigXml();
        try {
            config.write(ANON_MAP);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Problem saving anonymized names", e);
        }
    }
}