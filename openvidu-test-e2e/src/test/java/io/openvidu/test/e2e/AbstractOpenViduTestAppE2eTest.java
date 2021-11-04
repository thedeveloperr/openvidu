package io.openvidu.test.e2e;

import static org.openqa.selenium.OutputType.BASE64;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.jupiter.api.AfterEach;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mashape.unirest.http.HttpMethod;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.openvidu.java.client.OpenVidu;
import io.openvidu.java.client.OpenViduHttpException;
import io.openvidu.java.client.OpenViduJavaClientException;
import io.openvidu.java.client.VideoCodec;
import io.openvidu.test.browsers.BrowserUser;
import io.openvidu.test.browsers.ChromeUser;
import io.openvidu.test.browsers.EdgeUser;
import io.openvidu.test.browsers.FirefoxUser;
import io.openvidu.test.browsers.OperaUser;
import io.openvidu.test.browsers.utils.BrowserNames;
import io.openvidu.test.browsers.utils.CommandLineExecutor;
import io.openvidu.test.browsers.utils.CustomHttpClient;
import io.openvidu.test.browsers.utils.RecordingUtils;

public class AbstractOpenViduTestAppE2eTest {

	@ClassRule
	public static GenericContainer<?> chrome;

	@ClassRule
	public static GenericContainer<?> firefox;

	@ClassRule
	public static GenericContainer<?> opera;

	@ClassRule
	public static GenericContainer<?> edge;

	// Media server variables
	final protected static String KURENTO_IMAGE = "kurento/kurento-media-server";
	final protected static String MEDIASOUP_IMAGE = "openvidu/mediasoup-controller";
	protected static String MEDIA_SERVER_IMAGE = KURENTO_IMAGE + ":6.16.0";

	final protected String DEFAULT_JSON_SESSION = "{'id':'STR','object':'session','sessionId':'STR','createdAt':0,'mediaMode':'STR','recordingMode':'STR','defaultRecordingProperties':{'hasVideo':true,'frameRate':25,'hasAudio':true,'shmSize':536870912,'name':'','outputMode':'COMPOSED','resolution':'1280x720','recordingLayout':'BEST_FIT'},'customSessionId':'STR','connections':{'numberOfElements':0,'content':[]},'recording':false,'forcedVideoCodec':'STR','allowTranscoding':false}";
	final protected String DEFAULT_JSON_PENDING_CONNECTION = "{'id':'STR','object':'connection','type':'WEBRTC','status':'pending','connectionId':'STR','sessionId':'STR','createdAt':0,'activeAt':null,'location':null,'ip':null,'platform':null,'token':'STR','serverData':'STR','record':true,'role':'STR','kurentoOptions':null,'rtspUri':null,'adaptativeBitrate':null,'onlyPlayWithSubscribers':null,'networkCache':null,'clientData':null,'publishers':null,'subscribers':null}";
	final protected String DEFAULT_JSON_ACTIVE_CONNECTION = "{'id':'STR','object':'connection','type':'WEBRTC','status':'active','connectionId':'STR','sessionId':'STR','createdAt':0,'activeAt':0,'location':'STR','ip':'STR','platform':'STR','token':'STR','serverData':'STR','record':true,'role':'STR','kurentoOptions':null,'rtspUri':null,'adaptativeBitrate':null,'onlyPlayWithSubscribers':null,'networkCache':null,'clientData':'STR','publishers':[],'subscribers':[]}";
	final protected String DEFAULT_JSON_IPCAM_CONNECTION = "{'id':'STR','object':'connection','type':'IPCAM','status':'active','connectionId':'STR','sessionId':'STR','createdAt':0,'activeAt':0,'location':'STR','ip':'STR','platform':'IPCAM','token':null,'serverData':'STR','record':true,'role':null,'kurentoOptions':null,'rtspUri':'STR','adaptativeBitrate':true,'onlyPlayWithSubscribers':true,'networkCache':2000,'clientData':null,'publishers':[],'subscribers':[]}";
	final protected String DEFAULT_JSON_TOKEN = "{'id':'STR','token':'STR','connectionId':'STR','createdAt':0,'session':'STR','role':'STR','data':'STR','kurentoOptions':{}}";

	protected static String OPENVIDU_SECRET = "MY_SECRET";
	protected static String OPENVIDU_URL = "https://localhost:4443/";
	protected static String APP_URL = "http://localhost:4200/";
	protected static String EXTERNAL_CUSTOM_LAYOUT_URL = "http://localhost:5555";
	protected static String OPENVIDU_PRO_LICENSE = "not_valid";
	protected static String OPENVIDU_PRO_LICENSE_API = "not_valid";
	protected static String EXTERNAL_CUSTOM_LAYOUT_PARAMS = "sessionId,CUSTOM_LAYOUT_SESSION,secret,MY_SECRET";

	// https://hub.docker.com/r/selenium/standalone-chrome/tags
	protected static String CHROME_VERSION = "latest";
	// https://hub.docker.com/r/selenium/standalone-firefox/tags
	protected static String FIREFOX_VERSION = "latest";
	// https://hub.docker.com/r/selenium/standalone-opera/tags
	protected static String OPERA_VERSION = "latest";
	// https://hub.docker.com/r/selenium/standalone-edge/tags
	protected static String EDGE_VERSION = "latest";

	protected static Exception ex = null;
	protected final Object lock = new Object();

	protected static final Logger log = LoggerFactory.getLogger(OpenViduTestAppE2eTest.class);
	protected static final CommandLineExecutor commandLine = new CommandLineExecutor();
	protected static final String RECORDING_IMAGE = "openvidu/openvidu-recording";

	private Collection<MyUser> users = new HashSet<>();
	protected volatile static boolean isRecordingTest;
	protected volatile static boolean isKurentoRestartTest;

	protected static VideoCodec defaultForcedVideoCodec;
	protected static boolean defaultAllowTranscoding;

	protected static OpenVidu OV;

	protected RecordingUtils recordingUtils = new RecordingUtils();

	protected static void checkFfmpegInstallation() {
		String ffmpegOutput = commandLine.executeCommand("which ffmpeg");
		if (ffmpegOutput == null || ffmpegOutput.isEmpty()) {
			log.error("ffmpeg package is not installed in the host machine");
			Assert.fail();
			return;
		} else {
			log.info("ffmpeg is installed and accesible");
		}
	}

	@SuppressWarnings("resource")
	protected static void prepareBrowsers() {
		if (isRemote(BrowserNames.CHROME)) {
			chrome = new GenericContainer<>(DockerImageName.parse("selenium/standalone-chrome:" + CHROME_VERSION))
					.withSharedMemorySize(2147483648L).withFileSystemBind("/opt/openvidu", "/opt/openvidu");
			chrome.setPortBindings(Arrays.asList("6666:4444"));
			chrome.withExposedPorts(4444);
		} else {
			WebDriverManager.chromedriver().setup();
		}
		if (isRemote(BrowserNames.FIREFOX)) {
			firefox = new GenericContainer<>(DockerImageName.parse("selenium/standalone-firefox:" + FIREFOX_VERSION))
					.withSharedMemorySize(2147483648L).withFileSystemBind("/opt/openvidu", "/opt/openvidu");
			firefox.setPortBindings(Arrays.asList("6667:4444"));
			firefox.withExposedPorts(4444);
		} else {
			WebDriverManager.firefoxdriver().setup();
		}
		if (isRemote(BrowserNames.OPERA)) {
			opera = new GenericContainer<>(DockerImageName.parse("selenium/standalone-opera:" + OPERA_VERSION))
					.withSharedMemorySize(2147483648L).withFileSystemBind("/opt/openvidu", "/opt/openvidu");
			opera.setPortBindings(Arrays.asList("6668:4444"));
			opera.withExposedPorts(4444);
		} else {
			WebDriverManager.operadriver().setup();
		}
		if (isRemote(BrowserNames.EDGE)) {
			edge = new GenericContainer<>(DockerImageName.parse("selenium/standalone-edge:" + EDGE_VERSION))
					.withSharedMemorySize(2147483648L).withFileSystemBind("/opt/openvidu", "/opt/openvidu");
			edge.setPortBindings(Arrays.asList("6669:4444"));
			edge.withExposedPorts(4444);
		} else {
			WebDriverManager.edgedriver().setup();
		}
	}

	protected static void cleanFoldersAndSetUpOpenViduJavaClient() {
		try {
			log.info("Cleaning folder /opt/openvidu/recordings");
			FileUtils.cleanDirectory(new File("/opt/openvidu/recordings"));
		} catch (IOException e) {
			log.error(e.getMessage());
		}
		OV = new OpenVidu(OPENVIDU_URL, OPENVIDU_SECRET);
	}

	protected static void loadEnvironmentVariables() {
		String appUrl = System.getProperty("APP_URL");
		if (appUrl != null) {
			APP_URL = appUrl;
		}
		log.info("Using URL {} to connect to openvidu-testapp", APP_URL);

		String externalCustomLayoutUrl = System.getProperty("EXTERNAL_CUSTOM_LAYOUT_URL");
		if (externalCustomLayoutUrl != null) {
			EXTERNAL_CUSTOM_LAYOUT_URL = externalCustomLayoutUrl;
		}
		log.info("Using URL {} to connect to external custom layout", EXTERNAL_CUSTOM_LAYOUT_URL);

		String externalCustomLayoutParams = System.getProperty("EXTERNAL_CUSTOM_LAYOUT_PARAMS");
		if (externalCustomLayoutParams != null) {
			// Parse external layout parameters and build a URL formatted params string
			List<String> params = Stream.of(externalCustomLayoutParams.split(",", -1)).collect(Collectors.toList());
			if (params.size() % 2 != 0) {
				log.error(
						"Wrong configuration property EXTERNAL_CUSTOM_LAYOUT_PARAMS. Must be a comma separated list with an even number of elements. e.g: EXTERNAL_CUSTOM_LAYOUT_PARAMS=param1,value1,param2,value2");
				Assert.fail();
				return;
			} else {
				EXTERNAL_CUSTOM_LAYOUT_PARAMS = "";
				for (int i = 0; i < params.size(); i++) {
					if (i % 2 == 0) {
						// Param name
						EXTERNAL_CUSTOM_LAYOUT_PARAMS += params.get(i) + "=";
					} else {
						// Param value
						EXTERNAL_CUSTOM_LAYOUT_PARAMS += params.get(i);
						if (i < params.size() - 1) {
							EXTERNAL_CUSTOM_LAYOUT_PARAMS += "&";
						}
					}
				}
			}
		}
		log.info("Using URL {} to connect to external custom layout", EXTERNAL_CUSTOM_LAYOUT_PARAMS);

		String openviduUrl = System.getProperty("OPENVIDU_URL");
		if (openviduUrl != null) {
			OPENVIDU_URL = openviduUrl;
		}
		log.info("Using URL {} to connect to openvidu-server", OPENVIDU_URL);

		String openvidusecret = System.getProperty("OPENVIDU_SECRET");
		if (openvidusecret != null) {
			OPENVIDU_SECRET = openvidusecret;
		}
		log.info("Using secret {} to connect to openvidu-server", OPENVIDU_SECRET);

		String mediaServerImage = System.getProperty("MEDIA_SERVER_IMAGE");
		if (mediaServerImage != null) {
			MEDIA_SERVER_IMAGE = mediaServerImage;
		}
		log.info("Using media server {} for e2e tests", MEDIA_SERVER_IMAGE);

		String chromeVersion = System.getProperty("CHROME_VERSION");
		if (chromeVersion != null && !chromeVersion.isBlank()) {
			CHROME_VERSION = chromeVersion;
		}
		log.info("Using Chrome {}", CHROME_VERSION);

		String firefoxVersion = System.getProperty("FIREFOX_VERSION");
		if (firefoxVersion != null && !firefoxVersion.isBlank()) {
			FIREFOX_VERSION = firefoxVersion;
		}
		log.info("Using Firefox {}", FIREFOX_VERSION);

		String operaVersion = System.getProperty("OPERA_VERSION");
		if (operaVersion != null && !operaVersion.isBlank()) {
			OPERA_VERSION = operaVersion;
		}
		log.info("Using Opera {}", OPERA_VERSION);

		String edgeVersion = System.getProperty("EDGE_VERSION");
		if (edgeVersion != null && !edgeVersion.isBlank()) {
			EDGE_VERSION = edgeVersion;
		}
		log.info("Using Edge {}", EDGE_VERSION);

		String openviduProLicense = System.getProperty("OPENVIDU_PRO_LICENSE");
		if (openviduProLicense != null) {
			OPENVIDU_PRO_LICENSE = openviduProLicense;
		}

		String openviduProLicenseApi = System.getProperty("OPENVIDU_PRO_LICENSE_API");
		if (openviduProLicenseApi != null) {
			OPENVIDU_PRO_LICENSE_API = openviduProLicenseApi;
		}
	}

	protected MyUser setupBrowser(String browser) {

		BrowserUser browserUser;

		switch (browser) {
		case "chrome":
			setupBrowserAux(BrowserNames.CHROME, CHROME_VERSION, chrome, WebDriverManager.chromedriver(), false);
			browserUser = new ChromeUser("TestUser", 50, false);
			break;
		case "firefox":
			setupBrowserAux(BrowserNames.FIREFOX, FIREFOX_VERSION, firefox, WebDriverManager.firefoxdriver(), false);
			browserUser = new FirefoxUser("TestUser", 50, false);
			break;
		case "firefoxDisabledOpenH264":
			setupBrowserAux(BrowserNames.FIREFOX, FIREFOX_VERSION, firefox, WebDriverManager.firefoxdriver(), false);
			browserUser = new FirefoxUser("TestUser", 50, true);
			break;
		case "opera":
			setupBrowserAux(BrowserNames.OPERA, OPERA_VERSION, opera, WebDriverManager.operadriver(), false);
			browserUser = new OperaUser("TestUser", 50);
			break;
		case "edge":
			setupBrowserAux(BrowserNames.EDGE, EDGE_VERSION, edge, WebDriverManager.edgedriver(), false);
			browserUser = new EdgeUser("TestUser", 50);
			break;
		case "chromeAlternateScreenShare":
			setupBrowserAux(BrowserNames.CHROME, CHROME_VERSION, chrome, WebDriverManager.chromedriver(), false);
			browserUser = new ChromeUser("TestUser", 50, "OpenVidu TestApp", false);
			break;
		case "chromeAsRoot":
			setupBrowserAux(BrowserNames.CHROME, CHROME_VERSION, chrome, WebDriverManager.chromedriver(), false);
			browserUser = new ChromeUser("TestUser", 50, true);
			break;
		default:
			setupBrowserAux(BrowserNames.CHROME, CHROME_VERSION, chrome, WebDriverManager.chromedriver(), false);
			browserUser = new ChromeUser("TestUser", 50, false);
		}

		MyUser user = new MyUser(browserUser);

		user.getDriver().get(APP_URL);

		WebElement urlInput = user.getDriver().findElement(By.id("openvidu-url"));
		urlInput.clear();
		urlInput.sendKeys(OPENVIDU_URL);
		WebElement secretInput = user.getDriver().findElement(By.id("openvidu-secret"));
		secretInput.clear();
		secretInput.sendKeys(OPENVIDU_SECRET);

		user.getEventManager().startPolling();

		this.users.add(user);
		return user;
	}

	private void setupBrowserAux(BrowserNames browser, String version, GenericContainer<?> container,
			WebDriverManager webDriverManager, boolean forceRestart) {
		if (isRemote(browser)) {
			if (forceRestart && container.isRunning()) {
				container.stop();
			}
			if (!container.isRunning()) {
				container.start();
				container.waitingFor(Wait.forHttp("/wd/hub/status").forStatusCode(200));
			}
		}
	}

	private static boolean isRemote(BrowserNames browser) {
		String remoteUrl = null;
		switch (browser) {
		case CHROME:
			remoteUrl = System.getProperty("REMOTE_URL_CHROME");
			break;
		case FIREFOX:
			remoteUrl = System.getProperty("REMOTE_URL_FIREFOX");
			break;
		case OPERA:
			remoteUrl = System.getProperty("REMOTE_URL_OPERA");
			break;
		case EDGE:
			remoteUrl = System.getProperty("REMOTE_URL_EDGE");
			break;
		}
		return remoteUrl != null;
	}

	protected MyUser setupChromeWithFakeVideo(String absolutePathToVideoFile) {

		if (isRemote(BrowserNames.CHROME)) {
			setupBrowserAux(BrowserNames.CHROME, CHROME_VERSION, chrome, WebDriverManager.chromedriver(), true);
		}

		MyUser user = new MyUser(new ChromeUser("TestUser", 50, Paths.get(absolutePathToVideoFile)));
		user.getDriver().get(APP_URL);
		WebElement urlInput = user.getDriver().findElement(By.id("openvidu-url"));
		urlInput.clear();
		urlInput.sendKeys(OPENVIDU_URL);
		WebElement secretInput = user.getDriver().findElement(By.id("openvidu-secret"));
		secretInput.clear();
		secretInput.sendKeys(OPENVIDU_SECRET);
		user.getEventManager().startPolling();
		this.users.add(user);
		return user;
	}

	protected static void getDefaultTranscodingValues() throws Exception {
		CustomHttpClient restClient = new CustomHttpClient(OPENVIDU_URL, "OPENVIDUAPP", OPENVIDU_SECRET);
		JsonObject ovConfig = restClient.rest(HttpMethod.GET, "/openvidu/api/config", HttpStatus.SC_OK);
		defaultForcedVideoCodec = VideoCodec.valueOf(ovConfig.get("OPENVIDU_STREAMS_FORCED_VIDEO_CODEC").getAsString());
		defaultAllowTranscoding = ovConfig.get("OPENVIDU_STREAMS_ALLOW_TRANSCODING").getAsBoolean();
	}

	@AfterEach
	protected void dispose() {
		// Close all remaining OpenVidu sessions
		this.closeAllSessions(OV);
		// Remove all recordings
		if (isRecordingTest) {
			deleteAllRecordings(OV);
			isRecordingTest = false;
		}
		// Reset Media Server
		if (isKurentoRestartTest) {
			this.stopMediaServer(false);
			this.startMediaServer(true);
			isKurentoRestartTest = false;
		}
		// Dispose all browsers
		Iterator<MyUser> it = users.iterator();
		while (it.hasNext()) {
			MyUser u = it.next();
			u.dispose();
			it.remove();
		}
		// Stop and remove all browser containers if necessary
		stopContainerIfPossible(chrome);
		stopContainerIfPossible(firefox);
		stopContainerIfPossible(opera);
		stopContainerIfPossible(edge);
		// Reset REST client
		OV = new OpenVidu(OPENVIDU_URL, OPENVIDU_SECRET);
	}

	private void stopContainerIfPossible(GenericContainer<?> container) {
		if (container != null && container.isRunning()) {
			container.stop();
		}
	}

	protected void closeAllSessions(OpenVidu client) {
		try {
			client.fetch();
		} catch (OpenViduJavaClientException | OpenViduHttpException e) {
			log.error("Error fetching sessions: {}", e.getMessage());
		}
		client.getActiveSessions().forEach(session -> {
			try {
				session.close();
				log.info("Session {} successfully closed", session.getSessionId());
			} catch (OpenViduJavaClientException | OpenViduHttpException e) {
				log.error("Error closing session: {}", e.getMessage());
			}
		});
	}

	protected void deleteAllRecordings(OpenVidu client) {
		try {
			client.listRecordings().forEach(recording -> {
				try {
					client.deleteRecording(recording.getId());
					log.info("Recording {} successfully deleted", recording.getId());
				} catch (OpenViduJavaClientException | OpenViduHttpException e) {
					log.error("Error deleting recording: {}", e.getMessage());
				}
			});
		} catch (OpenViduJavaClientException | OpenViduHttpException e) {
			log.error("Error listing recordings: {}", e.getMessage());
		}
		removeAllRecordingContiners();
		try {
			FileUtils.cleanDirectory(new File("/opt/openvidu/recordings"));
		} catch (IOException e) {
			log.error(e.getMessage());
		}
	}

	protected void listEmptyRecordings(MyUser user) {
		// List existing recordings (empty)
		user.getDriver().findElement(By.id("list-recording-btn")).click();
		user.getWaiter()
				.until(ExpectedConditions.attributeToBe(By.id("api-response-text-area"), "value", "Recording list []"));
	}

	protected ExpectedCondition<Boolean> waitForVideoDuration(WebElement element, int durationInSeconds) {
		return new ExpectedCondition<Boolean>() {
			@Override
			public Boolean apply(WebDriver input) {
				return element.getAttribute("duration").matches(
						durationInSeconds - 1 + "\\.[5-9][0-9]{0,5}|" + durationInSeconds + "\\.[0-5][0-9]{0,5}");
			}
		};
	}

	protected void gracefullyLeaveParticipants(MyUser user, int numberOfParticipants) throws Exception {
		int accumulatedConnectionDestroyed = 0;
		for (int j = 1; j <= numberOfParticipants; j++) {
			user.getDriver().findElement(By.id("remove-user-btn")).sendKeys(Keys.ENTER);
			user.getEventManager().waitUntilEventReaches("sessionDisconnected", j);
			accumulatedConnectionDestroyed = (j != numberOfParticipants)
					? (accumulatedConnectionDestroyed + numberOfParticipants - j)
					: (accumulatedConnectionDestroyed);
			user.getEventManager().waitUntilEventReaches("connectionDestroyed", accumulatedConnectionDestroyed);
		}
	}

	protected String getBase64Screenshot(MyUser user) throws Exception {
		String screenshotBase64 = ((TakesScreenshot) user.getDriver()).getScreenshotAs(BASE64);
		return "data:image/png;base64," + screenshotBase64;
	}

	protected void startMediaServer(boolean waitUntilKurentoClientReconnection) {
		String command = null;
		if (MEDIA_SERVER_IMAGE.startsWith(KURENTO_IMAGE)) {
			log.info("Starting kurento");
			command = "docker run -e KMS_UID=$(id -u) --network=host --detach=true"
					+ " --volume=/opt/openvidu/recordings:/opt/openvidu/recordings " + MEDIA_SERVER_IMAGE;
		} else if (MEDIA_SERVER_IMAGE.startsWith(MEDIASOUP_IMAGE)) {
			log.info("Starting mediaSoup");
			command = "docker run --network=host --restart=always --detach=true --env=KMS_MIN_PORT=40000 --env=KMS_MAX_PORT=65535"
					+ " --env=OPENVIDU_PRO_LICENSE=" + OPENVIDU_PRO_LICENSE + " --env=OPENVIDU_PRO_LICENSE_API="
					+ OPENVIDU_PRO_LICENSE_API
					+ " --env=WEBRTC_LISTENIPS_0_ANNOUNCEDIP=172.17.0.1 --env=WEBRTC_LISTENIPS_0_IP=172.17.0.1"
					+ " --volume=/opt/openvidu/recordings:/opt/openvidu/recordings " + MEDIA_SERVER_IMAGE;
		} else {
			log.error("Unrecognized MEDIA_SERVER_IMAGE: {}", MEDIA_SERVER_IMAGE);
			System.exit(1);
		}
		commandLine.executeCommand(command);
		if (waitUntilKurentoClientReconnection) {
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	protected void stopMediaServer(boolean waitUntilNodeCrashedEvent) {
		final String dockerRemoveCmd = "docker ps -a | awk '{ print $1,$2 }' | grep GREP_PARAMETER | awk '{ print $1 }' | xargs -I {} docker rm -f {}";
		String grep = null;
		if (MEDIA_SERVER_IMAGE.startsWith(KURENTO_IMAGE)) {
			log.info("Stopping kurento");
			grep = KURENTO_IMAGE + ":";
		} else if (MEDIA_SERVER_IMAGE.startsWith(MEDIASOUP_IMAGE)) {
			log.info("Stopping mediasoup");
			grep = MEDIASOUP_IMAGE + ":";
		} else {
			log.error("Unrecognized MEDIA_SERVER_IMAGE: {}", MEDIA_SERVER_IMAGE);
			System.exit(1);
		}
		commandLine.executeCommand(dockerRemoveCmd.replaceFirst("GREP_PARAMETER", grep));
		if (waitUntilNodeCrashedEvent) {
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	protected void checkDockerContainerRunning(String imageName, int amount) {
		int number = Integer.parseInt(commandLine.executeCommand("docker ps | grep " + imageName + " | wc -l"));
		Assert.assertEquals("Wrong number of Docker containers for image " + imageName + " running", amount, number);
	}

	protected void removeAllRecordingContiners() {
		commandLine.executeCommand("docker ps -a | awk '{ print $1,$2 }' | grep " + RECORDING_IMAGE
				+ " | awk '{print $1 }' | xargs -I {} docker rm -f {}");
	}

	protected String mergeJson(String json, String newProperties, String[] removeProperties) {
		JsonObject jsonObj = JsonParser.parseString(json.replaceAll("'", "\"")).getAsJsonObject();
		JsonObject newJsonObj = JsonParser.parseString(newProperties.replaceAll("'", "\"")).getAsJsonObject();
		newJsonObj.entrySet().forEach(entry -> {
			jsonObj.remove(entry.getKey());
			jsonObj.add(entry.getKey(), entry.getValue());
		});
		for (String prop : removeProperties) {
			jsonObj.remove(prop);
		}
		return jsonObj.toString().replaceAll("\"", "'");
	}

	protected String getIndividualRecordingExtension() throws Exception {
		if (MEDIA_SERVER_IMAGE.contains(KURENTO_IMAGE)) {
			return "webm";
		}
		if (MEDIA_SERVER_IMAGE.contains(MEDIASOUP_IMAGE)) {
			return "mkv";
		} else {
			throw new Exception("Unknown media server");
		}
	}

	protected void waitUntilFileExistsAndIsBiggerThan(String absolutePath, int kbs, int maxSecondsWait)
			throws Exception {

		long startTime = System.currentTimeMillis();

		int interval = 100;
		int maxLoops = (maxSecondsWait * 1000) / interval;
		int loop = 0;
		long bytes = 0;

		boolean bigger = false;
		Path path = Paths.get(absolutePath);

		while (!bigger && loop < maxLoops) {
			bigger = Files.exists(path) && Files.isReadable(path);
			if (bigger) {
				try {
					bytes = Files.size(path);
				} catch (IOException e) {
					System.err.println("Error getting file size from " + path + ": " + e.getMessage());
				}
				bigger = (bytes / 1024) > kbs;
			}
			loop++;
			Thread.sleep(interval);
		}

		if (!bigger && loop >= maxLoops) {
			String errorMessage;
			if (!Files.exists(path)) {
				errorMessage = "File " + absolutePath + " does not exist and has not been created in " + maxSecondsWait
						+ " seconds";
			} else if (!Files.isReadable(path)) {
				errorMessage = "File " + absolutePath
						+ " exists but is not readable, and read permissions have not been granted in " + maxSecondsWait
						+ " seconds";
			} else {
				errorMessage = "File " + absolutePath + " did not reach a size of at least " + kbs + " KBs in "
						+ maxSecondsWait + " seconds. Last check was " + (bytes / 1024) + " KBs";
			}
			throw new Exception(errorMessage);
		} else {
			log.info("File " + absolutePath + " did reach a size of at least " + kbs + " KBs in "
					+ (System.currentTimeMillis() - startTime) + " ms (last checked size was " + (bytes / 1024)
					+ " KBs)");
		}
	}

}
