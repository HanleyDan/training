package com.hazelcast.platform.training.jet.jobs;

import static com.hazelcast.function.Functions.wholeItem;
import static com.hazelcast.jet.Traversers.traverseArray;
import static com.hazelcast.jet.aggregate.AggregateOperations.counting;
import static java.util.Comparator.comparingLong;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.platform.training.common.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import com.hazelcast.core.Hazelcast;

import com.hazelcast.jet.JetService;
import com.hazelcast.jet.config.JobConfig ;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;

/**
 * Jet Exercise 2 - Word Count Batch Job
 * Demonstrates a simple Word Count job in the Pipeline API. Inserts the
 * text of The Complete Works of William Shakespeare into a Hazelcast
 * IMap, then lets Jet count the words in it and write its findings to
 * another IMap. The example looks at Jet's output and prints the 100 most
 * frequent words.
 */
public class WordCount {

    private static final String BOOK_LINES = "bookLines";
    private static final String COUNTS = "counts";

    private JetService jet;

    private static Pipeline buildPipeline() {

        Pattern delimiter = Pattern.compile("\\W+");
        Pipeline p = Pipeline.create();
        p.readFrom(Sources.<Long, String>map(BOOK_LINES))
         .flatMap(e -> traverseArray(delimiter.split(e.getValue().toLowerCase())))
         .filter(word -> !word.isEmpty())
         .groupingKey(wholeItem())
         .aggregate(counting())
         .writeTo(Sinks.remoteMap(COUNTS,Utils.clientConfigForExternalHazelcast()));
        return p;
    }

    public static void main(String[] args) throws Exception {
        new WordCount().go();
    }


    private void go() {
        try {
            setup();
            System.out.println("\nCounting words... ");
            long start = System.nanoTime();

            //TODO: Use the jet instance to create a new job name 'WordCountBatch' and and wait for it to complete.
            Pipeline p = buildPipeline();
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName("WordCountBatch");
            jet.newJob(p, jobConfig).join();

            System.out.println("done in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " milliseconds.");
            Map<String, Long> results = Utils.remoteHazelcastInstance(Utils.clientConfigForExternalHazelcast()).getMap(COUNTS);
            checkResults(results);
            printResults(results);
        } finally {
            Hazelcast.shutdownAll();
        }
    }

    private void setup() {
        HazelcastInstance hz = Hazelcast.bootstrappedInstance();
        jet = hz.getJet();

        System.out.println("Loading The Complete Works of William Shakespeare");
        try {
            long[] lineNum = {0};
            Map<Long, String> bookLines = new HashMap<>();
            InputStream stream = getClass().getResourceAsStream("/books/shakespeare-complete-works.txt");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                reader.lines().forEach(line -> bookLines.put(++lineNum[0], line));
            }
            hz.getMap(BOOK_LINES).putAll(bookLines);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Long> checkResults(Map<String, Long> counts) {
        if (counts.get("the") != 27_843) {
            throw new AssertionError("Wrong count of 'the'");
        }
        System.out.println("Count of 'the' is valid");
        return counts;
    }

    private static void printResults(Map<String, Long> counts) {
        final int limit = 100;

        StringBuilder sb = new StringBuilder(String.format(" Top %d entries are:%n", limit));
        sb.append("/-------+---------\\\n");
        sb.append("| Count | Word    |\n");
        sb.append("|-------+---------|\n");
        counts.entrySet().stream()
                .sorted(comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .limit(limit)
                .forEach(e -> sb.append(String.format("|%6d | %-8s|%n", e.getValue(), e.getKey())));
        sb.append("\\-------+---------/\n");

        System.out.println(sb.toString());
    }
}
