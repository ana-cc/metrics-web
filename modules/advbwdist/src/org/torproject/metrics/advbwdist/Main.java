package org.torproject.metrics.advbwdist;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.torproject.descriptor.Descriptor;
import org.torproject.descriptor.DescriptorFile;
import org.torproject.descriptor.DescriptorReader;
import org.torproject.descriptor.DescriptorSourceFactory;
import org.torproject.descriptor.NetworkStatusEntry;
import org.torproject.descriptor.RelayNetworkStatusConsensus;
import org.torproject.descriptor.ServerDescriptor;

public class Main {
  public static void main(String[] args) throws IOException {

    /* Parse server descriptors, not keeping a parse history, and memorize
     * the advertised bandwidth for every server descriptor. */
    DescriptorReader descriptorReader =
        DescriptorSourceFactory.createDescriptorReader();
    descriptorReader.addDirectory(
        new File("../../shared/in/relay-descriptors/server-descriptors"));
    Iterator<DescriptorFile> descriptorFiles =
        descriptorReader.readDescriptors();
    Map<String, Long> serverDescriptors =
        new HashMap<String, Long>();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      for (Descriptor descriptor : descriptorFile.getDescriptors()) {
        if (!(descriptor instanceof ServerDescriptor)) {
          continue;
        }
        ServerDescriptor serverDescriptor = (ServerDescriptor) descriptor;
        String digest = serverDescriptor.getServerDescriptorDigest();
        long advertisedBandwidth = Math.min(Math.min(
            serverDescriptor.getBandwidthRate(),
            serverDescriptor.getBandwidthBurst()),
            serverDescriptor.getBandwidthObserved());
        serverDescriptors.put(digest.toUpperCase(), advertisedBandwidth);
      }
    }

    /* Parse consensuses, keeping a parse history. */
    descriptorReader = DescriptorSourceFactory.createDescriptorReader();
    descriptorReader.addDirectory(
        new File("../../shared/in/relay-descriptors/consensuses"));
    descriptorReader.setExcludeFiles(
        new File("status/parsed-consensuses"));
    descriptorFiles = descriptorReader.readDescriptors();
    File resultsFile = new File("stats/advbwdist-validafter.csv");
    resultsFile.getParentFile().mkdirs();
    boolean writeHeader = !resultsFile.exists();
    BufferedWriter bw = new BufferedWriter(new FileWriter(resultsFile,
        true));
    if (writeHeader) {
      bw.write("valid_after,isexit,relay,percentile,advbw\n");
    }
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      for (Descriptor descriptor : descriptorFile.getDescriptors()) {
        if (!(descriptor instanceof RelayNetworkStatusConsensus)) {
          continue;
        }

        /* Parse server descriptor digests from consensus and look up
         * advertised bandwidths. */
        RelayNetworkStatusConsensus consensus =
            (RelayNetworkStatusConsensus) descriptor;
        String validAfter = dateTimeFormat.format(
            consensus.getValidAfterMillis());
        List<Long> advertisedBandwidthsAllRelays = new ArrayList<Long>(),
            advertisedBandwidthsExitsOnly = new ArrayList<Long>();
        for (NetworkStatusEntry relay :
            consensus.getStatusEntries().values()) {
          if (!relay.getFlags().contains("Running")) {
            continue;
          }
          String serverDescriptorDigest = relay.getDescriptor().
              toUpperCase();
          if (!serverDescriptors.containsKey(serverDescriptorDigest)) {
            continue;
          }
          long advertisedBandwidth = serverDescriptors.get(
              serverDescriptorDigest);
          advertisedBandwidthsAllRelays.add(advertisedBandwidth);
          if (relay.getFlags().contains("Exit") &&
              !relay.getFlags().contains("BadExit")) {
            advertisedBandwidthsExitsOnly.add(advertisedBandwidth);
          }
        }

        /* Write advertised bandwidths of n-th fastest relays/exits. */
        Collections.sort(advertisedBandwidthsAllRelays,
            Collections.reverseOrder());
        Collections.sort(advertisedBandwidthsExitsOnly,
            Collections.reverseOrder());
        int[] fastestRelays = new int[] { 1, 2, 3, 5, 10, 20, 30, 50, 100,
            200, 300, 500, 1000, 2000, 3000, 5000 };
        for (int fastestRelay : fastestRelays) {
          if (advertisedBandwidthsAllRelays.size() >= fastestRelay) {
            bw.write(String.format("%s,,%d,,%d%n", validAfter,
                fastestRelay,
                advertisedBandwidthsAllRelays.get(fastestRelay - 1)));
          }
        }
        for (int fastestRelay : fastestRelays) {
          if (advertisedBandwidthsExitsOnly.size() >= fastestRelay) {
            bw.write(String.format("%s,TRUE,%d,,%d%n", validAfter,
                fastestRelay,
                advertisedBandwidthsExitsOnly.get(fastestRelay - 1)));
          }
        }

        /* Write advertised bandwidth percentiles of relays/exits. */
        Collections.sort(advertisedBandwidthsAllRelays);
        Collections.sort(advertisedBandwidthsExitsOnly);
        int[] percentiles = new int[] { 0, 1, 2, 3, 5, 9, 10, 20, 25, 30,
            40, 50, 60, 70, 75, 80, 90, 91, 95, 97, 98, 99, 100 };
        if (!advertisedBandwidthsAllRelays.isEmpty()) {
          for (int percentile : percentiles) {
            bw.write(String.format("%s,,,%d,%d%n", validAfter,
                percentile, advertisedBandwidthsAllRelays.get(
                ((advertisedBandwidthsAllRelays.size() - 1) *
                percentile) / 100)));
          }
        }
        if (!advertisedBandwidthsExitsOnly.isEmpty()) {
          for (int percentile : percentiles) {
            bw.write(String.format("%s,TRUE,,%d,%d%n", validAfter,
                percentile, advertisedBandwidthsExitsOnly.get(
                ((advertisedBandwidthsExitsOnly.size() - 1) *
                percentile) / 100)));
          }
        }
      }
    }
    bw.close();
  }
}

