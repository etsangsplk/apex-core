/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.stram.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.*;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.XmlType;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.WebResource;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import com.datatorrent.api.util.ObjectMapperString;
import com.datatorrent.stram.client.WebServicesVersionConversion.IncompatibleVersionException;

import com.datatorrent.stram.debug.TupleRecorder;
import com.datatorrent.stram.util.FSPartFileCollection;
import com.datatorrent.stram.util.WebServicesClient;
import com.datatorrent.stram.webapp.StramWebServices;

/**
 * <p>RecordingsAgent class.</p>
 *
 * @author David Yan <david@datatorrent.com>
 * @since 0.3.2
 */
public final class RecordingsAgent extends FSPartFileAgent
{
  private static final Logger LOG = LoggerFactory.getLogger(RecordingsAgent.class);
  private static final long MAX_LIMIT_TUPLES = 1000;
  private boolean localMode = false;

  public static class RecordingInfo
  {
    public long startTime;
    public String containerId;
    public String appId;
    public String operatorId;
    public long totalTuples = 0;
    public List<PortInfo> ports;
    public boolean ended = false;
    public List<TupleRecorder.Range> windowIdRanges;
    public Map<String, Object> properties;
  }

  private static class RecordingsIndexLine extends IndexLine
  {
    public List<TupleRecorder.Range> windowIdRanges;
    public long fromTime;
    public long toTime;
    public long tupleCount;
    public Map<String, MutableLong> portTupleCount;
  }

  @XmlType(name = "port_info") // not really used, but this is to shut jackson up for conflicting xml names with TupleRecorder.PortInfo
  public static class PortInfo extends TupleRecorder.PortInfo
  {
    public long tupleCount = 0;
  }

  public static class WindowTuplesInfo
  {
    public long windowId;
    public List<TupleInfo> tuples = new ArrayList<TupleInfo>();
  }

  public static class TuplesInfo
  {
    public long startOffset;
    public List<WindowTuplesInfo> tuples = new ArrayList<WindowTuplesInfo>();
  }

  public static class TupleInfo
  {
    public String portId;
    public ObjectMapperString data;

    TupleInfo(String portId, String data)
    {
      this.portId = portId;
      this.data = new ObjectMapperString(data);
    }

  }

  public void setLocalMode(boolean localMode)
  {
    this.localMode = localMode;
  }

  @Override
  public void setup() throws IOException
  {
    if (localMode) {
      fs = new RawLocalFileSystem();
      try {
        fs.initialize(new URI("file:/test"), new Configuration());
      }
      catch (Exception ex) {
      }
    }
    else {
      fs = FileSystem.get(new Configuration());
    }
  }

  public String getRecordingsDirectory(String appId, String opId)
  {
    return getRecordingsDirectory(appId) + Path.SEPARATOR + opId;
  }

  public String getRecordingsDirectory(String appId)
  {
    String appPath = getAppPath(appId);
    if (appPath == null) {
      return null;
    }
    return appPath + Path.SEPARATOR + "recordings";
  }

  public String getRecordingDirectory(String appId, String opId, long startTime)
  {
    String dir = getRecordingsDirectory(appId, opId);
    return (dir == null) ? null : dir + Path.SEPARATOR + String.valueOf(startTime);
  }

  @Override
  protected RecordingsIndexLine parseIndexLine(String line) throws JSONException
  {
    RecordingsIndexLine info = new RecordingsIndexLine();

    if (line.startsWith("E")) {
      info.isEndLine = true;
      return info;
    }
    line = line.trim();

    info.windowIdRanges = new ArrayList<TupleRecorder.Range>();
    info.portTupleCount = new HashMap<String, MutableLong>();

    int cursor = 2;
    int cursor2 = line.indexOf(':', cursor);
    info.partFile = line.substring(cursor, cursor2);
    cursor = cursor2 + 1;
    cursor2 = line.indexOf(':', cursor);
    String timeRange = line.substring(cursor, cursor2);
    String[] tmp = timeRange.split("-");
    info.fromTime = Long.valueOf(tmp[0]);
    info.toTime = Long.valueOf(tmp[1]);
    cursor = cursor2 + 1;
    cursor2 = line.indexOf(':', cursor);
    if (cursor2 < 0) {
      info.tupleCount = Long.valueOf(line.substring(cursor));
      return info;
    }
    info.tupleCount = Long.valueOf(line.substring(cursor, cursor2));
    cursor = cursor2 + 1;
    if (!line.substring(cursor, cursor + 2).equals("T:")) {
      return info;
    }
    cursor += 2;
    cursor2 = line.indexOf(':', cursor);

    String windowRangesString = line.substring(cursor, cursor2);
    String[] windowRanges = windowRangesString.split(",");
    for (String windowRange : windowRanges) {
      String[] hilow = windowRange.split("-");
      long low = Long.valueOf(hilow[0]);
      long hi = Long.valueOf(hilow[1]);
      info.windowIdRanges.add(new TupleRecorder.Range(low, hi));
    }
    cursor = cursor2 + 1;
    cursor2 = line.indexOf(':', cursor);
    int size = Integer.valueOf(line.substring(cursor, cursor2));
    cursor = cursor2 + 1;
    cursor2 = cursor + size;
    JSONObject json = new JSONObject(line.substring(cursor, cursor2));
    Iterator<?> keys = json.keys();
    while (keys.hasNext()) {
      String portIndex = (String)keys.next();
      long tupleCount = json.getLong(portIndex);
      if (!info.portTupleCount.containsKey(portIndex)) {
        info.portTupleCount.put(portIndex, new MutableLong(tupleCount));
      }
      else {
        info.portTupleCount.get(portIndex).add(tupleCount);
      }
    }
    return info;
  }

  private Set<String> getRunningContainerIds(String appId)
  {
    Set<String> result = new HashSet<String>();
    String url = "http://" + resourceManagerWebappAddress + "/proxy/" + appId + "/ws/v1/stram/containers";
    try {
      WebServicesClient webServicesClient = new WebServicesClient();
      JSONObject response = webServicesClient.process(url, JSONObject.class, new WebServicesClient.GetWebServicesHandler<JSONObject>());
      Object containersObj = response.get("containers");
      JSONArray containers;
      if (containersObj instanceof JSONArray) {
        containers = (JSONArray)containersObj;
      }
      else {
        containers = new JSONArray();
        containers.put(containersObj);
      }
      int len = containers.length();
      for (int i = 0; i < len; i++) {
        JSONObject container = containers.getJSONObject(i);
        if (container.getString("state").equals("ACTIVE")) {
          result.add(container.getString("id"));
        }
      }
    }
    catch (Exception ex) {
      LOG.warn("Error getting running containers for {}. Assuming no containers are running.", appId);
    }
    return result;
  }

  public List<RecordingInfo> getRecordingInfo(String appId)
  {
    List<RecordingInfo> result = new ArrayList<RecordingInfo>();
    String dir = getRecordingsDirectory(appId);
    if (dir == null) {
      return result;
    }
    Path path = new Path(dir);
    try {
      FileStatus fileStatus = fs.getFileStatus(path);

      if (!fileStatus.isDirectory()) {
        return result;
      }
      RemoteIterator<LocatedFileStatus> ri = fs.listLocatedStatus(path);
      while (ri.hasNext()) {
        LocatedFileStatus lfs = ri.next();
        if (lfs.isDirectory()) {
          try {
            String opId = lfs.getPath().getName();
            result.addAll(getRecordingInfo(appId, opId));
          }
          catch (NumberFormatException ex) {
            continue;
          }
        }
      }
    }
    catch (IOException ex) {
      LOG.warn("Got exception when getting recording info", ex);
      return result;
    }

    return result;
  }

  public List<RecordingInfo> getRecordingInfo(String appId, String opId)
  {
    Set<String> containers = getRunningContainerIds(appId);
    return getRecordingInfoHelper(appId, opId, containers);
  }

  private List<RecordingInfo> getRecordingInfoHelper(String appId, String opId, Set<String> containers)
  {
    ArrayList<RecordingInfo> result = new ArrayList<RecordingInfo>();
    String dir = getRecordingsDirectory(appId, opId);
    if (dir == null) {
      return result;
    }
    Path path = new Path(dir);
    try {
      FileStatus fileStatus = fs.getFileStatus(path);

      if (!fileStatus.isDirectory()) {
        return result;
      }
      RemoteIterator<LocatedFileStatus> ri = fs.listLocatedStatus(path);
      while (ri.hasNext()) {
        LocatedFileStatus lfs = ri.next();
        if (lfs.isDirectory()) {
          try {
            Long startTime = Long.valueOf(lfs.getPath().getName());
            result.add(getRecordingInfoHelper(appId, opId, startTime, containers));
          }
          catch (NumberFormatException ex) {
            continue;
          }
        }
      }
    }
    catch (IOException ex) {
      LOG.warn("Got exception when getting recording info", ex);
      return result;
    }

    return result;
  }

  public RecordingInfo getRecordingInfo(String appId, String opId, long startTime)
  {
    Set<String> containers = getRunningContainerIds(appId);
    return getRecordingInfoHelper(appId, opId, startTime, containers);
  }

  private RecordingInfo getRecordingInfoHelper(String appId, String opId, long startTime, Set<String> containers)
  {
    RecordingInfo info = new RecordingInfo();
    info.appId = appId;
    info.operatorId = opId;
    try {
      String dir = getRecordingDirectory(appId, opId, startTime);
      if (dir == null) {
        throw new Exception("recording directory is null");
      }

      Path path = new Path(dir);
      JSONObject json;

      FileStatus fileStatus = fs.getFileStatus(path);
      HashMap<String, PortInfo> portMap = new HashMap<String, PortInfo>();
      if (!fileStatus.isDirectory()) {
        throw new Exception(path + " is not a directory");
      }

      // META file processing
      FSDataInputStream in = fs.open(new Path(dir, FSPartFileCollection.META_FILE));
      BufferedReader br = new BufferedReader(new InputStreamReader(in));
      String line;
      line = br.readLine();
      if (!line.equals("1.2")) {
        throw new Exception("Unexpected line: " + line);
      }
      line = br.readLine();
      json = new JSONObject(line);
      info.startTime = json.getLong("startTime");
      info.containerId = json.optString("containerId");
      info.properties = new HashMap<String, Object>();

      if (!StringUtils.isBlank(info.containerId) && !containers.contains(info.containerId)) {
        info.ended = true;
      }

      json = json.optJSONObject("properties");
      if (json != null) {
        @SuppressWarnings("unchecked")
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          // ugly 2 lines of code below since JSONObject.get(key).toString() doesn't give you json representation for plain strings
          String strValue = json.isNull(key) ? null : json.optString(key);
          info.properties.put(key, strValue != null ? strValue : new ObjectMapperString(json.get(key).toString()));
        }
      }
      info.ports = new ArrayList<PortInfo>();
      while ((line = br.readLine()) != null) {
        PortInfo portInfo = new PortInfo();
        json = new JSONObject(line);
        portInfo.id = json.getInt("id");
        portInfo.name = json.getString("name");
        portInfo.type = json.getString("type");
        portInfo.streamName = json.getString("streamName");
        info.ports.add(portInfo);
        portMap.put(String.valueOf(portInfo.id), portInfo);
      }

      // INDEX file processing
      in = fs.open(new Path(dir, FSPartFileCollection.INDEX_FILE));
      IndexFileBufferedReader ifbr = new IndexFileBufferedReader(new InputStreamReader(in), dir);
      info.windowIdRanges = new ArrayList<TupleRecorder.Range>();
      long prevHiWindowId = -1;
      RecordingsIndexLine indexLine;
      while ((indexLine = (RecordingsIndexLine)ifbr.readIndexLine()) != null) {
        if (indexLine.isEndLine) {
          info.ended = true;
        }
        else {
          info.totalTuples += indexLine.tupleCount;
          for (Map.Entry<String, MutableLong> entry : indexLine.portTupleCount.entrySet()) {
            PortInfo portInfo = portMap.get(entry.getKey());
            if (portInfo == null) {
              throw new Exception("port info does not exist for " + entry.getKey());
            }
            portInfo.tupleCount += entry.getValue().longValue();
          }
          for (TupleRecorder.Range r : indexLine.windowIdRanges) {
            if (info.windowIdRanges.isEmpty()) {
              TupleRecorder.Range range = new TupleRecorder.Range();
              range.low = r.low;
              info.windowIdRanges.add(range);
            }
            else if (prevHiWindowId + 1 != r.low) {
              TupleRecorder.Range range = info.windowIdRanges.get(info.windowIdRanges.size() - 1);
              range.high = prevHiWindowId;
              range = new TupleRecorder.Range();
              range.low = r.low;
              info.windowIdRanges.add(range);
            }
            prevHiWindowId = r.high;
          }
        }
      }
      in.close();
      if (!info.windowIdRanges.isEmpty()) {
        TupleRecorder.Range range = info.windowIdRanges.get(info.windowIdRanges.size() - 1);
        range.high = prevHiWindowId;
      }
    }
    catch (Exception ex) {
      LOG.warn("Got exception when getting recording info", ex);
      return null;
    }

    return info;
  }

  private enum QueryType
  {
    OFFSET, WINDOW, TIME
  };

  public TuplesInfo getTuplesInfoByTime(String appId, String opId, long startTime, long fromTime, long toTime, long limit, String[] ports)
  {
    return getTuplesInfo(appId, opId, startTime, fromTime, toTime, limit, ports, QueryType.TIME);
  }

  public TuplesInfo getTuplesInfoByOffset(String appId, String opId, long startTime, long offset, long limit, String[] ports)
  {
    return getTuplesInfo(appId, opId, startTime, offset, 0, limit, ports, QueryType.OFFSET);
  }

  public TuplesInfo getTuplesInfoByWindow(String appId, String opId, long startTime, long startWindow, long limit, String[] ports)
  {
    return getTuplesInfo(appId, opId, startTime, startWindow, 0, limit, ports, QueryType.WINDOW);
  }

  private TuplesInfo getTuplesInfo(String appId, String opId, long startTime, long low, long high, long limit, String[] ports, QueryType queryType)
  {
    TuplesInfo info = new TuplesInfo();
    info.startOffset = -1;
    String dir = getRecordingDirectory(appId, opId, startTime);
    if (dir == null) {
      return null;
    }

    try {
      FSDataInputStream in = fs.open(new Path(dir, FSPartFileCollection.INDEX_FILE));
      IndexFileBufferedReader ifbr = new IndexFileBufferedReader(new InputStreamReader(in), dir);
      long currentOffset = 0;
      boolean readPartFile = false;
      if (limit == 0 || limit > MAX_LIMIT_TUPLES) {
        limit = MAX_LIMIT_TUPLES;
      }
      long numRemainingTuples = limit;
      long currentTimestamp = 0;
      RecordingsIndexLine indexLine;
      while ((indexLine = (RecordingsIndexLine)ifbr.readIndexLine()) != null) {
        if (indexLine.isEndLine) {
          continue;
        }
        long currentWindowLow;
        long currentWindowHigh;
        long numTuples = 0;

        if (ports == null || ports.length == 0) {
          numTuples = indexLine.tupleCount;
        }
        else {
          for (String port : ports) {
            if (indexLine.portTupleCount.containsKey(port)) {
              numTuples += indexLine.portTupleCount.get(port).longValue();
            }
            else {
              LOG.warn("Port index {} is not found, ignoring...", port);
            }
          }
        }
        currentWindowLow = indexLine.windowIdRanges.get(0).low;
        currentWindowHigh = indexLine.windowIdRanges.get(indexLine.windowIdRanges.size() - 1).high;

        if (!readPartFile) {
          if (queryType == QueryType.WINDOW) {
            if (currentWindowLow > low) {
              break;
            }
            else if (currentWindowLow <= low && low <= currentWindowHigh) {
              readPartFile = true;
            }
          }
          else if (queryType == QueryType.OFFSET) {
            if (currentOffset + numTuples > low) {
              readPartFile = true;
            }
          }
          else { // time
            if (indexLine.fromTime > low) {
              break;
            }
            else if (indexLine.fromTime <= low && low <= indexLine.toTime) {
              readPartFile = true;
            }
          }
        }

        if (readPartFile) {
          FSDataInputStream partIn = fs.open(new Path(dir, indexLine.partFile));
          BufferedReader partBr = new BufferedReader(new InputStreamReader(partIn));
          String partLine;
          long tmpOffset = currentOffset;
          // advance until offset is reached
          while ((partLine = partBr.readLine()) != null) {
            int partCursor = 2;
            if (partLine.startsWith("B:")) {
              int partCursor2 = partLine.indexOf(':', partCursor);
              currentTimestamp = Long.valueOf(partLine.substring(partCursor, partCursor2));
              partCursor = partCursor2 + 1;
              currentWindowLow = Long.valueOf(partLine.substring(partCursor));
              if (limit != numRemainingTuples) {
                WindowTuplesInfo wtinfo;
                wtinfo = new WindowTuplesInfo();
                wtinfo.windowId = currentWindowLow;
                info.tuples.add(wtinfo);
              }
            }
            else if (partLine.startsWith("T:")) {
              int partCursor2 = partLine.indexOf(':', partCursor);
              currentTimestamp = Long.valueOf(partLine.substring(partCursor, partCursor2));
              partCursor = partCursor2 + 1;
              partCursor2 = partLine.indexOf(':', partCursor);
              String port = partLine.substring(partCursor, partCursor2);
              boolean portMatch = (ports == null) || (ports.length == 0) || Arrays.asList(ports).contains(port);
              partCursor = partCursor2 + 1;

              if (portMatch
                      && ((queryType == QueryType.WINDOW && currentWindowLow >= low)
                      || (queryType == QueryType.OFFSET && tmpOffset >= low)
                      || (queryType == QueryType.TIME && currentTimestamp >= low))) {

                if (numRemainingTuples > 0) {
                  if (info.startOffset == -1) {
                    info.startOffset = tmpOffset;
                  }
                  WindowTuplesInfo wtinfo;
                  if (info.tuples.isEmpty() || info.tuples.get(info.tuples.size() - 1).windowId != currentWindowLow) {
                    wtinfo = new WindowTuplesInfo();
                    wtinfo.windowId = currentWindowLow;
                    info.tuples.add(wtinfo);
                  }
                  else {
                    wtinfo = info.tuples.get(info.tuples.size() - 1);
                  }

                  partCursor2 = partLine.indexOf(':', partCursor);
                  int size = Integer.valueOf(partLine.substring(partCursor, partCursor2));
                  partCursor = partCursor2 + 1;
                  //partCursor2 = partCursor + size;
                  String tupleValue = partLine.substring(partCursor);
                  wtinfo.tuples.add(new TupleInfo(port, tupleValue));
                  numRemainingTuples--;
                }
                else {
                  return info;
                }
              }
              if (portMatch) {
                tmpOffset++;
              }
            }
          }
        }
        currentOffset += numTuples;
        if (numRemainingTuples == 0 || (queryType == QueryType.TIME && currentTimestamp > high)) {
          return info;
        }
      }
    }
    catch (Exception ex) {
      LOG.warn("Got exception when getting tuples info", ex);
      return null;
    }

    return info;
  }

  public String startRecording(String appId, String opId, String portName) throws IncompatibleVersionException
  {
    WebServicesClient webServicesClient = new WebServicesClient();
    WebResource wr = getStramWebResource(webServicesClient, appId);
    if (wr == null) {
      throw new WebApplicationException(404);
    }
    LOG.debug("Start recording requested for {}.{}", opId, portName);
    try {
      final JSONObject request = new JSONObject();
      String path = StramWebServices.PATH_PHYSICAL_PLAN_OPERATORS + "/" + opId;
      if (!StringUtils.isBlank(portName)) {
        path += "/ports/" + portName;
      }
      path += "/" + StramWebServices.PATH_RECORDINGS_START;
      return webServicesClient.process(wr.path(path), String.class,
                                       new WebServicesClient.WebServicesHandler<String>()
      {
        @Override
        public String process(WebResource webResource, Class<String> clazz)
        {
          return webResource.type(MediaType.APPLICATION_JSON).post(clazz, request);
        }

      });
    }
    catch (Exception ex) {
      LOG.error("Exception caught", ex);
      return null;
    }
  }

  public String stopRecording(String appId, String opId, String portName) throws IncompatibleVersionException
  {
    WebServicesClient webServicesClient = new WebServicesClient();
    WebResource wr = getStramWebResource(webServicesClient, appId);
    if (wr == null) {
      throw new WebApplicationException(404);
    }
    try {
      final JSONObject request = new JSONObject();
      String path = StramWebServices.PATH_PHYSICAL_PLAN_OPERATORS + "/" + opId;
      if (!StringUtils.isBlank(portName)) {
        path += "/ports/" + portName;
      }
      path += "/" + StramWebServices.PATH_RECORDINGS_STOP;
      return webServicesClient.process(wr.path(path), String.class,
                                       new WebServicesClient.WebServicesHandler<String>()
      {
        @Override
        public String process(WebResource webResource, Class<String> clazz)
        {
          return webResource.type(MediaType.APPLICATION_JSON).post(clazz, request);
        }

      });
    }
    catch (Exception ex) {
      LOG.error("Exception caught", ex);
      return null;
    }
  }

}
