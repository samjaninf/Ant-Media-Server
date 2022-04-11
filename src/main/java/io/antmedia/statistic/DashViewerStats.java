package io.antmedia.statistic;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import io.antmedia.AppSettings;
import io.antmedia.datastore.db.DataStore;
import io.antmedia.datastore.db.DataStoreFactory;
import io.antmedia.datastore.db.IDataStoreFactory;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.datastore.db.types.ConnectionEvent;
import io.antmedia.muxer.IAntMediaStreamHandler;
import io.vertx.core.Vertx;

public class DashViewerStats implements IStreamStats, ApplicationContextAware {

	protected static Logger logger = LoggerFactory.getLogger(DashViewerStats.class);
	
	public static final String BEAN_NAME = "dash.viewerstats";

	private DataStore dataStore;
	
	private Vertx vertx;
	
	private DataStoreFactory dataStoreFactory;

	public static final int DEFAULT_TIME_PERIOD_FOR_VIEWER_COUNT = 10000;
	/**
	 * Time period in milliseconds to check if viewer is dropped
	 */
	private int timePeriodMS = DEFAULT_TIME_PERIOD_FOR_VIEWER_COUNT;

	Map<String, Map<String, Long>> streamsViewerMap = new ConcurrentHashMap<>();
	Map<String, String> sessionId2subscriberId = new ConcurrentHashMap<>();
	Map<String, Integer> increaseCounterMap = new ConcurrentHashMap<>();
	
	private Object lock = new Object();

	/**
	 * Time out value in milliseconds, it is regarded as user is not watching stream 
	 * if last request time is older than timeout value
	 */
	private int timeoutMS = 20000;

	@Override
	public void registerNewViewer(String streamId, String sessionId, String subscriberId) 
	{
		//do not block the thread, run in vertx event queue 
		vertx.runOnContext(h -> {
			
			synchronized (lock) {
				//synchronize with database update calculations, because some odd cases may happen
				
				Map<String, Long> viewerMap = streamsViewerMap.get(streamId);
				if (viewerMap == null) {
					viewerMap = new ConcurrentHashMap<>();
				}
				if (!viewerMap.containsKey(sessionId)) 
				{
					int streamIncrementCounter = getIncreaseCounterMap(streamId);
					streamIncrementCounter++;
					increaseCounterMap.put(streamId, streamIncrementCounter);
					
				}
				viewerMap.put(sessionId, System.currentTimeMillis());
				streamsViewerMap.put(streamId, viewerMap);
				if(subscriberId != null) {
					// map sessionId to subscriberId
					sessionId2subscriberId.put(sessionId, subscriberId);
					// add a connected event to the subscriber
					ConnectionEvent event = new ConnectionEvent();
					event.setEventType(ConnectionEvent.CONNECTED_EVENT);
					Date curDate = new Date();
					event.setTimestamp(curDate.getTime());
					getDataStore().addSubscriberConnectionEvent(streamId, subscriberId, event);
				}
			}
			
		});
		
	}
	
	public int getIncreaseCounterMap(String streamId) 
	{
		Integer increaseCounter = increaseCounterMap.get(streamId);
		return increaseCounter != null ? increaseCounter : 0;
	}

	@Override
	public int getViewerCount(String streamId) {
		Map<String, Long> viewerMap = streamsViewerMap.get(streamId);
		int viewerCount = 0;
		if (viewerMap != null) 
		{
			viewerCount = viewerMap.size();
		}
		return viewerCount;
	}
	
	public int getTotalViewerCount() {
		int viewerCount = 0;
		for (Map<String, Long> map : streamsViewerMap.values()) {
			viewerCount += map.size();
		}
		return viewerCount;
	}
	public void setVertx(Vertx vertx) {
		this.vertx = vertx;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)  {
		dataStoreFactory = (DataStoreFactory) applicationContext.getBean(IDataStoreFactory.BEAN_NAME);
		
		vertx = (Vertx) applicationContext.getBean(IAntMediaStreamHandler.VERTX_BEAN_NAME);

		
		AppSettings settings = (AppSettings)applicationContext.getBean(AppSettings.BEAN_NAME);
		timeoutMS = getTimeoutMSFromSettings(settings, timeoutMS);
		
		
		vertx.setPeriodic(DEFAULT_TIME_PERIOD_FOR_VIEWER_COUNT, yt-> 
		{
			synchronized (lock) {
				
				Iterator<Entry<String, Map<String, Long>>> streamIterator = streamsViewerMap.entrySet().iterator();
				
				Iterator<Entry<String, Long>> viewerIterator;
				Entry<String, Map<String, Long>> streamViewerEntry;
				Map<String, Long> viewerMapEntry;
				
				long now = System.currentTimeMillis();
				
				while (streamIterator.hasNext()) 
				{
					streamViewerEntry = streamIterator.next();
					
					String streamId = streamViewerEntry.getKey();
					Broadcast broadcast = getDataStore().get(streamId);
					
					boolean isBroadcasting = false;
					
					// Check if it's deleted.
					// This case for the deleted streams(zombi streams)
					if(broadcast != null) {
					
						int numberOfDecrement = 0;
						
						viewerMapEntry = streamViewerEntry.getValue();
						viewerIterator = viewerMapEntry.entrySet().iterator();
					
						while (viewerIterator.hasNext()) 
						{
							Entry<String, Long> viewer = viewerIterator.next();
	
							if (viewer.getValue() < (now - getTimeoutMS())) 
							{
								// regard it as not a viewer
								viewerIterator.remove();
								numberOfDecrement++;
								
								String sessionId = viewer.getKey();
								String subscriberId = sessionId2subscriberId.get(sessionId);
								// set subscriber status to not connected
								if(subscriberId != null) {
									// add a disconnected event to the subscriber
									ConnectionEvent event = new ConnectionEvent();
									event.setEventType(ConnectionEvent.DISCONNECTED_EVENT);
									Date curDate = new Date();
									event.setTimestamp(curDate.getTime());
									getDataStore().addSubscriberConnectionEvent(streamId, subscriberId, event);
								}
							}
						}
						
						if(broadcast.getStatus().equals(IAntMediaStreamHandler.BROADCAST_STATUS_BROADCASTING)) {
							isBroadcasting = true;
						}
					
						numberOfDecrement = -1 * numberOfDecrement;
	
						int numberOfIncrement = getIncreaseCounterMap(streamId);
						if((numberOfIncrement != 0 || numberOfDecrement != 0) && isBroadcasting) {
							
							int dashDiffCount = numberOfIncrement + numberOfDecrement;
	
							logger.info("Update DASH viewer in stream ID:{} increment count:{} decrement count:{} diff:{}", streamId, numberOfIncrement, numberOfDecrement, dashDiffCount);
	
							getDataStore().updateDASHViewerCount(streamViewerEntry.getKey(), dashDiffCount);
							increaseCounterMap.put(streamId, 0);
						}
					}

					if (!isBroadcasting) {
						// set all connection status information about the subscribers of the stream to false
						viewerMapEntry = streamViewerEntry.getValue();
						viewerIterator = viewerMapEntry.entrySet().iterator();
						while (viewerIterator.hasNext()) {
							Entry<String, Long> viewer = viewerIterator.next();
							
							String sessionId = viewer.getKey();
							String subscriberId = sessionId2subscriberId.get(sessionId);
							// set subscriber status to not connected
							if(subscriberId != null) {
								// add a disconnected event to the subscriber
								ConnectionEvent event = new ConnectionEvent();
								event.setEventType(ConnectionEvent.DISCONNECTED_EVENT);
								Date curDate = new Date();
								event.setTimestamp(curDate.getTime());
								getDataStore().addSubscriberConnectionEvent(streamId, subscriberId, event);
							}
						}
						
						streamIterator.remove();
						increaseCounterMap.remove(streamId);
					}
				}
			}
		});	
	}
	
	public void resetDASHViewerMap(String streamID) {
		Iterator<Entry<String, Long>> viewerIterator;
		Map<String, Long> viewerMapEntry = streamsViewerMap.get(streamID);
		if(viewerMapEntry != null) {
			// remove all the subscribers associated with the sessions in the stream 
			viewerIterator = viewerMapEntry.entrySet().iterator();
			while (viewerIterator.hasNext()) {
				Entry<String, Long> viewer = viewerIterator.next();
				
				String sessionId = viewer.getKey();
				if(sessionId2subscriberId.containsKey(sessionId)) {
					sessionId2subscriberId.remove(sessionId);					
				}
			}
			
			streamsViewerMap.get(streamID).clear();
			streamsViewerMap.remove(streamID);
			logger.info("Reset DASH Stream ID: {} removed successfully", streamID);			
		}
		else {
			logger.info("Reset DASH Stream ID: {} remove failed or null", streamID);
		}
	}

	public Map<String, String> getSessionId2subscriberId() {
		return sessionId2subscriberId;
	}

	public void setSessionId2subscriberId(Map<String, String> sessionId2subscriberId) {
		this.sessionId2subscriberId = sessionId2subscriberId;
	}

	public static int getTimeoutMSFromSettings(AppSettings settings, int defaultValue) {
		int newTimePeriodMS = defaultValue;
		//TODO Check it again
		String dashTime = settings.getDashFragmentDuration(); 
		if (dashTime != null && !dashTime.isEmpty()) {
			try {
				newTimePeriodMS = Integer.valueOf(dashTime) * 10 * 1000;
			}
			catch (Exception e) {
				logger.error(e.getMessage());
			}
		}
		return newTimePeriodMS;
	}

	public void setTimePeriodMS(int timePeriodMS) {
		this.timePeriodMS = timePeriodMS;
	}
	public int getTimePeriodMS() {
		return timePeriodMS;
	}

	public int getTimeoutMS() {
		return timeoutMS;
	}

	public DataStore getDataStore() {
		if (dataStore == null) {
			dataStore = getDataStoreFactory().getDataStore();
		}
		return dataStore;
	}
	
	public void setDataStore(DataStore dataStore) {
		this.dataStore = dataStore;
	}
	
	
	public DataStoreFactory getDataStoreFactory() {
		return dataStoreFactory;
	}
	
	public void setDataStoreFactory(DataStoreFactory dataStoreFactory) {
		this.dataStoreFactory = dataStoreFactory;
	}

}