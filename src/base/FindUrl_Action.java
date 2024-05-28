package base;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.swing.JOptionPane;

import org.apache.commons.lang3.StringUtils;

import com.bit4woo.utilbox.burp.HelperPlus;
import com.bit4woo.utilbox.utils.SwingUtils;
import com.bit4woo.utilbox.utils.TextUtils;
import com.bit4woo.utilbox.utils.UrlUtils;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.IContextMenuInvocation;
import burp.IExtensionHelpers;
import burp.IHttpRequestResponse;
import burp.threadRequester;

public class FindUrl_Action implements ActionListener {
	private IContextMenuInvocation invocation;
	public IExtensionHelpers helpers;
	public PrintWriter stdout;
	public PrintWriter stderr;
	public IBurpExtenderCallbacks callbacks;
	public BurpExtender burp;

	public static final String[] blackHostList = {"www.w3.org", "ns.adobe.com", "iptc.org", "openoffice.org"
			, "schemas.microsoft.com", "schemas.openxmlformats.org", "sheetjs.openxmlformats.org","registry.npmjs.org"
			,"json-schema.org","jmespath.org"};

	public static final List<String> blackPath = TextUtils.textToLines("text/css\r\n"
			+ "	text/html\r\n"
			+ "	text/plain\r\n"
			+ "	image/pdf\r\n");


	private static Proxy proxy;
	public static Map<String,String> BaseUrlMap = new HashMap<>();

	public FindUrl_Action(BurpExtender burp, IContextMenuInvocation invocation) {
		this.burp = burp;
		this.invocation = invocation;
		this.helpers = burp.helpers;
		this.callbacks = BurpExtender.callbacks;
		this.stderr = BurpExtender.stderr;
		this.stdout = BurpExtender.stdout;
	}

	public FindUrl_Action() {
		this.helpers = BurpExtender.helpers;

	}


	public void doSendRequest(String baseurl,List<String> urlPath,String refererToUse) {
		try {
			BlockingQueue<RequestTask> inputQueue = new LinkedBlockingQueue<>();

			try {
				for (String url : urlPath) {
					if (!url.startsWith("http://") && !url.startsWith("https://")) {
						if (url.startsWith("/")) {
							url = url.replaceFirst("/", "");
						}
						if (url.startsWith("./")) {
							url = url.replaceFirst("\\./", "");
						}
						url = baseurl + url; //baseurl统一以“/”结尾；url统一删除“/”的开头
						inputQueue.put(new RequestTask(url, RequestType.GET));
						inputQueue.put(new RequestTask(url, RequestType.POST));
						inputQueue.put(new RequestTask(url, RequestType.JSON));
					}
				}
			} catch (Exception e) {
				e.printStackTrace(BurpExtender.getStderr());
			}

			doRequest(inputQueue, refererToUse);
		} catch (Exception e1) {
			e1.printStackTrace(stderr);
		}
	}

	@Override
	public void actionPerformed(ActionEvent event) {
		Runnable requestRunner = new Runnable() {
			@Override
			public void run() {
				IHttpRequestResponse[] messages = invocation.getSelectedMessages();
				if (messages == null || messages.length <= 0) {
					return;
				}
				String targetBaseUrl = getTargetSiteBaseUrl(messages[0]);

				List<String> urls = FindAllUrls(messages[0]);

				Set<String> baseUrls = findPossibleBaseURL(urls);

				if (baseUrls.size() <= 0) {
					return;
				}

				String baseurl = choseAndEditBaseURL(baseUrls);

				if (null == baseurl) {
					return;
				}

				urls = choseURLPath(urls);
				if (urls.size()<=0) return; 

				doSendRequest(baseurl,urls,targetBaseUrl);
			}
		};
		new Thread(requestRunner).start();
	}

	/**
	 * 根据当前web的baseUrl找JS，特征就是referer以它开头
	 * @param message
	 * @return
	 */
	public static List<String> FindAllUrls(IHttpRequestResponse message) {
		String targetBaseUrl = getTargetSiteBaseUrl(message);

		List<String> urls = findUrls(message.getResponse());

		HelperPlus getter = BurpExtender.getHelperPlus();
		IHttpRequestResponse[] messages = BurpExtender.getCallbacks().getSiteMap(null);
		for (IHttpRequestResponse item : messages) {
			URL url = getter.getFullURL(item);
			if (url == null || (!url.toString().toLowerCase().endsWith(".js") && !url.toString().toLowerCase().endsWith(".js.map"))) {
				continue;
			}

			int code = getter.getStatusCode(item);
			String referUrl = getter.getHeaderValueOf(true, item, "Referer");
			if (referUrl == null || code <= 0) {
				continue;
			}

			if (referUrl.toLowerCase().startsWith(targetBaseUrl.toLowerCase())) {
				urls.addAll(findUrls(item.getResponse()));
			}
		}
		return urls;
	}

	/**
	 * @param message
	 * @return
	 */
	public static String getTargetSiteBaseUrl(IHttpRequestResponse message) {
		HelperPlus getter = BurpExtender.getHelperPlus();

		String current_referUrl = getter.getHeaderValueOf(true, message, "Referer");
		String current_fullUrl = getter.getFullURL(message).toString();

		if (current_referUrl != null) {
			//认为当前数据包是前端触发的
			return UrlUtils.getBaseUrl(current_referUrl);
		} else {
			//认为其是当前数据包是浏览器地址栏访问直接触发的
			return UrlUtils.getBaseUrl(current_fullUrl);
		}
	}

	public static List<String> findUrls(byte[] content) {
		List<String> urls = new ArrayList<>();

		if (content == null) {
			return urls;
		}else {
			return findUrls(new String(content));
		}
	}

	/**
	 * 在数据包中查找URL
	 * @param content
	 * @return
	 */
	public static List<String> findUrls(String content) {
		List<String> urls = new ArrayList<>();
		if (StringUtils.isEmpty(content)) {
			return urls;
		}

		content = TextUtils.decodeAll(content);
		urls.addAll(UrlUtils.grepUrlsWithProtocol(content));
		urls.addAll(UrlUtils.grepUrlPathNotStartWithSlashInQuotes(content));
		urls.addAll(UrlUtils.grepUrlsInQuotes(content));
		urls = cleanUrls(urls);

		return urls;
	}

	/**
	 * 多线程执行请求
	 *
	 * @param inputQueue
	 */
	public void doRequest(BlockingQueue<RequestTask> inputQueue, String referUrl) {
		if (proxy == null) {
			proxy = Proxy.inputProxy();
		}
		if (proxy == null) {
			return;
		}

		int max = threadNumberShouldUse(inputQueue.size());

		for (int i = 0; i <= max; i++) {
			threadRequester requester = new threadRequester(inputQueue, proxy.getHost(), proxy.getPort(), referUrl, i);
			requester.start();
		}
	}

	/**
	 * 根据已有的域名梳理，预估应该使用的线程数
	 * 假设1个任务需要1秒钟。线程数在1-100之间，如何选择线程数使用最小的时间？
	 *
	 * @param domainNum
	 * @return
	 */
	public static int threadNumberShouldUse(int domainNum) {

		int tmp = (int) Math.sqrt(domainNum);
		if (tmp <= 1) {
			return 1;
		} else if (tmp >= 10) {
			return 10;
		} else {
			return tmp;
		}
	}

	public static Set<String> findPossibleBaseURL(List<String> urls) {
		Set<String> baseURLs = new HashSet<>();
		for (String tmpurl : urls) {
			//这部分提取的是含有协议头的完整URL地址
			if (tmpurl.toLowerCase().startsWith("http://")
					|| tmpurl.toLowerCase().startsWith("https://")) {

				baseURLs.add(tmpurl);
			}
		}
		return baseURLs;
	}



	public static String choseAndEditBaseURL(Set<String> inputs) {

		ArrayList<String> tmpList = new ArrayList<String>(inputs);
		Collections.sort(tmpList);
		int n = inputs.size() + 1;
		String[] possibleValues = new String[n];

		// Copying contents of domains to arr[]
		System.arraycopy(tmpList.toArray(), 0, possibleValues, 0, n - 1);
		possibleValues[n - 1] = "Let Me Input";

		String selectedValue = (String) JOptionPane.showInputDialog(null,
				"Chose Base URL", "Chose And Edit Base URL",
				JOptionPane.INFORMATION_MESSAGE, null,
				possibleValues, possibleValues[0]);
		if (null != selectedValue) {
			String baseUrl = JOptionPane.showInputDialog("Confirm The Base URL", selectedValue);
			if (baseUrl == null) {
				return null;
			}
			if (!baseUrl.endsWith("/")) {
				baseUrl = baseUrl.trim() + "/";
			}
			return baseUrl.trim();
		}
		return selectedValue;
	}


	public static List<String> choseURLPath(List<String> urls) {

		Collections.sort(urls);

		String text = SwingUtils.showTextAreaDialog(String.join(System.lineSeparator(), urls));
		if (StringUtils.isEmpty(text)) {
			return new ArrayList<String>();
		}else {
			return TextUtils.textToLines(text);
		}
	}


	public static List<String> cleanUrls(List<String> urls) {

		urls = TextUtils.deduplicate(urls);
		Iterator<String> it  = urls.iterator();
		while (it.hasNext()) {
			String urlItem = it.next();
			if (UrlUtils.uselessExtension(urlItem)) {
				it.remove();
			}
			if (blackPath.contains(urlItem)) {
				it.remove();
			}
			try {
				String host = new URL(urlItem).getHost();
				if (Arrays.asList(blackHostList).contains(host)) {
					it.remove();
				}
			} catch (Exception E) {
				continue;
			}
		}
		return urls;
	}
}
