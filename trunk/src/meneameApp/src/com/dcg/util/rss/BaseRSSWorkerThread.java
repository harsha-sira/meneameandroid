package com.dcg.util.rss;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import com.dcg.app.ApplicationMNM;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

/**
 * Base class used to parse an RSS Feed.
 * TODO: Pass a file object
 * @author Moritz Wundke (b.thax.dcg@gmail.com)
 */
abstract public class BaseRSSWorkerThread extends Thread {
	
	/** Definitions of a completed message */
	public static String COMPLETED_KEY = "completed";
	public static int COMPLETED_OK = 0;
	public static int COMPLETED_FAILED = -1;
	
	/** Key which defines any state when caching a feed */
	public static String ERROR_KEY = "error";
	public static int ERROR_SUCCESSFULL = 0;
	public static int ERROR_FAILED = -1;
	public static String ERROR_MESSAGE_KEY = "error_msg";
	
	/** Global Application */
	private HttpClient mHTTPClient = null;
	
	/** log tag for this class */
	private static final String TAG = "BaseRSSWorkerThread";
	
	/** if true we requested a stop, so do not handle any request stuff */
	private boolean mbStopRequested = false;
	
	/** Semaphore used when the thread starts working*/
	private Semaphore mSemaphore = null;
	
	/** Feed URL */
	protected String mFeedURL = "";
	
	/** Handler used to send messages to the activity that will handle our work */
	private Handler mHandler;
	
	/** class that handles the caching process */
	protected String mFeedCacherClassName = "";
	
	/** class we use to parse the feed */
	private String mFeedParserClassName = "";
	
	/** Default handler we use to parse the current feed */
	private RSSParser mFeedParser = null;
	
	/** thread msg data */
	protected Bundle mData = new Bundle();
	private int mMaxItems;
	
	/**
	 * 
	 * @param Activity ParentActivity, holds the semaphore to make this thread save
	 */
	public BaseRSSWorkerThread() {
		super();
		
		ApplicationMNM.addLogCat(TAG);
	}
	
	/**
	 * Setup all data this class needs, we can not use it's constructor because we invoke this class!
	 * @param maxItems 
	 * @param globalApp
	 * @param handler
	 * @param feedURL
	 * @param threadSemaphore
	 */
	public void setupWorker( HttpClient HTTPClient, int maxItems, Handler handler, String feedURL, Semaphore threadSemaphore ) {
		mFeedURL = feedURL;
		mSemaphore = threadSemaphore;
		mHTTPClient = HTTPClient;
		mHandler = handler;
		mMaxItems = maxItems;
	}
	
	public String getmFeedParserClassName() {
		return mFeedParserClassName;
	}

	public void setmFeedParserClassName(String mFeedParserClassName) {
		this.mFeedParserClassName = mFeedParserClassName;
	}

	/**
	 * Request this thread to stop what it's doing
	 */
	public void requestStop() {
		mbStopRequested = true;
		if ( mFeedParser != null )
		{
			// Stop parsing
			mFeedParser.requestStop();
		}
	}
	
	@Override
	public void run() {
		mbStopRequested = false;
		try {
			try {
				ApplicationMNM.logCat(TAG, "Aquirering semaphore " + mSemaphore.toString());
				mSemaphore.acquire();
			} catch (InterruptedException e) {
					return;
			}
			guardedRun();
		} catch (InterruptedException e) {
			// fall thru and exit normally
		} finally {
			// Set our catched app to null so GC can clean the refernce
			if ( mFeedParser != null )
			{
				// We got all needed data, clear internal references
				mFeedParser.clearReferences();
				mFeedParser = null;
			}
			ApplicationMNM.logCat(TAG, "Releasing semaphore " + mSemaphore.toString());
			mSemaphore.release();
		}
		
		// Release last refs
		mSemaphore = null;
		mHandler = null;
	}
	
	/**
	 * Will get the input stream reader the feed reader will use to parse it
	 * @return
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	protected InputStreamReader getInputStreamReader() throws ClientProtocolException, IOException, URISyntaxException 
	{
		HttpGet request = new HttpGet();
		
		mHTTPClient.getConnectionManager().closeExpiredConnections();
		mHTTPClient.getConnectionManager().closeIdleConnections(10, TimeUnit.SECONDS );
		
		request.setURI(new URI(mFeedURL));
		ApplicationMNM.logCat(TAG, "Starting request " + request.toString());
		
		HttpResponse response = mHTTPClient.execute(request);
		mHTTPClient = null;
		
		if ( response != null )
		{
			return new InputStreamReader(response.getEntity().getContent());
		}
		return null;
	}
	
	private void guardedRun() throws InterruptedException {
		// At this point we are thread safe
		if ( mHandler != null && !mbStopRequested )
		{
			Message msg = mHandler.obtainMessage();
			mData = new Bundle();
			InputStreamReader streamReader = null;
			try {
				streamReader = getInputStreamReader();

				if ( streamReader != null )
				{
					// Start processing the RSS file
					processResult(msg, streamReader);				
					
					// look for any error
					if ( isDataValid() && mFeedParser != null )
					{
						if ( ! mbStopRequested )
						{
							// Let us make any post processing stuff
							postProcessResult(msg, mFeedParser.getFeed());
		
							// All fine
							ApplicationMNM.logCat(TAG, "Finished parsing: " + mFeedURL);
						}
						else
						{
							ApplicationMNM.logCat(TAG, "Stop requested while parsing: " + mFeedURL);
						}
						mData.putInt(COMPLETED_KEY, COMPLETED_OK);
					}
					else
					{
						mData.putInt(COMPLETED_KEY, COMPLETED_FAILED);
					}		
				}
				else
				{
					ApplicationMNM.logCat(TAG, "Failed to parse: " + mFeedURL);
					mData.putInt(COMPLETED_KEY, COMPLETED_FAILED);
				}
			} catch (Exception e) {
				e.printStackTrace();
				ApplicationMNM.logCat(TAG, "Failed to parse: " + e.toString());
				mData.putInt(COMPLETED_KEY, COMPLETED_FAILED);
				setErrorMessage(e.toString());
			} finally {
				if (streamReader != null) {
					try {
						streamReader.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				// Send final message
				msg.setData(mData);
				sendMessage(msg);
			}
		}
	}
	
	/**
	 * Send a message to our handler
	 * @param msg
	 */
	private void sendMessage( Message msg ) {
		if ( mHandler != null && !mbStopRequested )
		{
			mHandler.sendMessage(msg);
		}
	}
	
	/**
	 * Checks if the Bundle has any error set or not
	 * @return 
	 */
	protected boolean isDataValid() {
		return mData.getInt(ERROR_KEY) == ERROR_SUCCESSFULL;
	}
	
	/**
	 * Returns the current error message registered with the Bundle
	 * @return
	 */
	protected String getErrorMessage() {
		return mData.getString(ERROR_MESSAGE_KEY);
	}
	
	/**
	 * Set a new error message into our current data Bundle
	 * @param error
	 */
	protected void setErrorMessage( String error ) {
		mData.putInt(ERROR_KEY, ERROR_FAILED);
		mData.putString(ERROR_MESSAGE_KEY, error);
	}
	
	private void createRSSHandler() {
		// Try to create the RSS handler
		try {
			mFeedParser = (RSSParser)Class.forName(this.mFeedParserClassName).newInstance();
			ApplicationMNM.logCat(TAG, "RSS-Parser created: " + mFeedParser.toString());
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			setErrorMessage(e.toString());
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			setErrorMessage(e.toString());
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			setErrorMessage(e.toString());
		}
	}
	
	/**
	 * Called once we parsed the file, the input reader has been reset before.
	 */
	protected void postProcessResult( Message msg, Feed parsedFeed ) {
		// Nothing to be done by default
	}
	
	/**
	 * Will parse the incoming data and save it into a Bundle
	 * @param msg 
	 * @param page
	 * @return 
	 * @return
	 */
	private void processResult( Message msg, InputStreamReader inputStreamReader )
	{
		// Create rss handler
		createRSSHandler();
		
		// Check if we got a valid parser
		if ( mFeedParser == null )
		{
			// Set error message and return
			setErrorMessage("Could not create parser of class " + mFeedParserClassName);
			return;
		}
		
		// Setup parser and parse!
		mFeedParser.setInputStream( inputStreamReader );
		mFeedParser.setWorkerThread(this);
		
		              
		mFeedParser.setMaxItems(mMaxItems);
		mFeedParser.parse();
		
		msg.obj = mFeedParser.getFeed();
	}
}