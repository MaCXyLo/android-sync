/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.davdroid.webdav;

import android.util.Log;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDeleteHC4;
import org.apache.http.client.methods.HttpGetHC4;
import org.apache.http.client.methods.HttpOptionsHC4;
import org.apache.http.client.methods.HttpPutHC4;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ByteArrayEntityHC4;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.auth.BasicSchemeHC4;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProviderHC4;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicLineParserHC4;
import org.apache.http.util.EntityUtilsHC4;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import at.bitfire.davdroid.DAVUtils;
import at.bitfire.davdroid.URIUtils;
import at.bitfire.davdroid.resource.iCalendar;
import at.bitfire.davdroid.webdav.DavProp.Comp;
import ezvcard.VCardVersion;
import lombok.Cleanup;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;


/**
 * Represents a WebDAV resource (file or collection).
 * This class is used for all CalDAV/CardDAV communcation.
 */
@ToString
public class WebDavResource {
	private static final String TAG = "davdroid.WebDavResource";

	public enum PutMode {
		ADD_DONT_OVERWRITE,
		UPDATE_DONT_OVERWRITE
	}

	// location of this resource
	@Getter protected URI location;

	// DAV capabilities (DAV: header) and allowed DAV methods (set for OPTIONS request)
	protected Set<String>	capabilities = new HashSet<>(),
							methods = new HashSet<>();

	// list of members (only for collections)
	@Getter Properties properties = new Properties();
	@Getter protected List<WebDavResource> members;

	// content (available after GET)
	@Getter protected byte[] content;

	protected CloseableHttpClient httpClient;
	protected HttpClientContext context;


	public WebDavResource(CloseableHttpClient httpClient, URI baseURI) {
		this.httpClient = httpClient;
		location = baseURI;

		context = HttpClientContext.create();
		context.setCredentialsProvider(new BasicCredentialsProviderHC4());
	}

	public WebDavResource(CloseableHttpClient httpClient, URI baseURI, String username, String password, boolean preemptive) {
		this(httpClient, baseURI);

		context.getCredentialsProvider().setCredentials(
				AuthScope.ANY,
				new UsernamePasswordCredentials(username, password)
		);

		if (preemptive) {
            HttpHost host = new HttpHost(baseURI.getHost(), baseURI.getPort(), baseURI.getScheme());
			Log.d(TAG, "Using preemptive authentication (not compatible with Digest auth)");
			AuthCache authCache = context.getAuthCache();
			if (authCache == null)
				authCache = new BasicAuthCache();
			authCache.put(host, new BasicSchemeHC4());
			context.setAuthCache(authCache);
		}
	}

	public WebDavResource(WebDavResource parent) {		// copy constructor: based on existing WebDavResource, reuse settings
		// reuse httpClient, context and location (no deep copy)
		httpClient = parent.httpClient;
		context = parent.context;
		location = parent.location;
	}

	public WebDavResource(WebDavResource parent, URI url) {
		this(parent);
		location = parent.location.resolve(url);
	}

    /**
     * Creates a WebDavResource representing a member of the parent collection.
     * @param parent Parent collection
     * @param member File name of the member, unescaped. This may contain ":" without leading "./"!
     *               To create a new collection with a relative path that may not be a member, use the
     *               WebDavResource(WebDavResource parent, URI url) constructor.
     * @throws URISyntaxException
     */
	public WebDavResource(WebDavResource parent, String member) throws URISyntaxException {
		this(parent);
		location = parent.location.resolve(new URI(null, null, "./" + member, null));
	}

	public WebDavResource(WebDavResource parent, String member, String eTag) throws URISyntaxException {
		this(parent, member);
		properties.eTag = eTag;
	}


	/* feature detection */

	public void options() throws URISyntaxException, IOException, HttpException {
		HttpOptionsHC4 options = new HttpOptionsHC4(location);

		@Cleanup CloseableHttpResponse response = httpClient.execute(options, context);
		checkResponse(response);

		Header[] allowHeaders = response.getHeaders("Allow");
		for (Header allowHeader : allowHeaders)
			methods.addAll(Arrays.asList(allowHeader.getValue().split(", ?")));

		Header[] capHeaders = response.getHeaders("DAV");
		for (Header capHeader : capHeaders)
			capabilities.addAll(Arrays.asList(capHeader.getValue().split(", ?")));
	}

	public boolean supportsDAV(String capability) {
		return capabilities.contains(capability);
	}

	public boolean supportsMethod(String method) {
		return methods.contains(method);
	}


	/* file hierarchy methods */

	public String getName() {
		String[] names = StringUtils.split(location.getPath(), "/");
		return names[names.length - 1];
	}


	/* collection operations */

	public void propfind(HttpPropfind.Mode mode) throws URISyntaxException, IOException, DavException, HttpException {
		@Cleanup CloseableHttpResponse response = null;

		// processMultiStatus() requires knowledge of the actual content location,
		// so we have to handle redirections manually and create a new request for the new location
		for (int i = context.getRequestConfig().getMaxRedirects(); i > 0; i--) {
			HttpPropfind propfind = new HttpPropfind(location, mode);
			response = httpClient.execute(propfind, context);

			if (response.getStatusLine().getStatusCode()/100 == 3) {
				location = DavRedirectStrategy.getLocation(propfind, response, context);
				Log.i(TAG, "Redirection on PROPFIND; trying again at new content URL: " + location);
				// don't forget to throw away the unneeded response content
				HttpEntity entity = response.getEntity();
				if (entity != null) { @Cleanup InputStream content = entity.getContent(); }
			} else
				break;		// answer was NOT a redirection, continue
		}
		if (response == null)
			throw new DavNoContentException();

		checkResponse(response);		// will also handle Content-Location
		processMultiStatus(response);
	}

	public void multiGet(DavMultiget.Type type, String[] names) throws URISyntaxException, IOException, DavException, HttpException {
		@Cleanup CloseableHttpResponse response = null;

		// processMultiStatus() requires knowledge of the actual content location,
		// so we have to handle redirections manually and create a new request for the new location
		for (int i = context.getRequestConfig().getMaxRedirects(); i > 0; i--) {
			// build multi-get XML request
			List<String> hrefs = new LinkedList<>();
			for (String name : names)
				// name may contain "%" which have to be encoded → use non-quoting URI constructor and getRawPath()
				// name may also contain ":", so prepend "./" because even the non-quoting URI constructor parses after constructing
				// DAVdroid ensures that collections always have a trailing slash, so "./" won't go down in directory hierarchy
				hrefs.add(location.resolve(new URI(null, null, "./" + name, null)).getRawPath());
			DavMultiget multiget = DavMultiget.newRequest(type, hrefs.toArray(new String[hrefs.size()]));

			StringWriter writer = new StringWriter();
			try {
				Serializer serializer = new Persister();
				serializer.write(multiget, writer);
			} catch (Exception ex) {
				Log.e(TAG, "Couldn't create XML multi-get request", ex);
				throw new DavException("Couldn't create multi-get request");
			}

			// submit REPORT request
			HttpReport report = new HttpReport(location, writer.toString());
			response = httpClient.execute(report, context);

			if (response.getStatusLine().getStatusCode()/100 == 3) {
				location = DavRedirectStrategy.getLocation(report, response, context);
				Log.i(TAG, "Redirection on REPORT multi-get; trying again at new content URL: " + location);

				// don't forget to throw away the unneeded response content
				HttpEntity entity = response.getEntity();
				if (entity != null) { @Cleanup InputStream content = entity.getContent(); }
			} else
				break;		// answer was NOT a redirection, continue
		}
		if (response == null)
			throw new DavNoContentException();

		checkResponse(response);		// will also handle Content-Location
		processMultiStatus(response);
	}

	public void report(String query) throws IOException, HttpException, DavException {
		HttpReport report = new HttpReport(location, query);
		report.setHeader("Depth", "1");

		@Cleanup CloseableHttpResponse response = httpClient.execute(report, context);
		if (response == null)
			throw new DavNoContentException();

		checkResponse(response);
		processMultiStatus(response);
	}


	/* resource operations */

	public void get(String acceptedMimeTypes) throws URISyntaxException, IOException, HttpException, DavException {
		HttpGetHC4 get = new HttpGetHC4(location);
		get.addHeader("Accept", acceptedMimeTypes);

		@Cleanup CloseableHttpResponse response = httpClient.execute(get, context);
		checkResponse(response);

		HttpEntity entity = response.getEntity();
		if (entity == null)
			throw new DavNoContentException();

		properties.contentType = ContentType.get(entity);
		content = EntityUtilsHC4.toByteArray(entity);
	}

	// returns the ETag of the created/updated resource, if available (null otherwise)
	public String put(byte[] data, PutMode mode) throws URISyntaxException, IOException, HttpException {
		HttpPutHC4 put = new HttpPutHC4(location);
		put.setEntity(new ByteArrayEntityHC4(data));

		switch (mode) {
		case ADD_DONT_OVERWRITE:
			put.addHeader("If-None-Match", "*");
			break;
		case UPDATE_DONT_OVERWRITE:
			put.addHeader("If-Match", (properties.eTag != null) ? properties.eTag : "*");
			break;
		}

		if (properties.contentType != null)
			put.addHeader("Content-Type", properties.contentType.toString());

		@Cleanup CloseableHttpResponse response = httpClient.execute(put, context);
		checkResponse(response);

		Header eTag = response.getLastHeader("ETag");
		if (eTag != null)
			return eTag.getValue();

		return null;
	}

	public void delete() throws URISyntaxException, IOException, HttpException {
		HttpDeleteHC4 delete = new HttpDeleteHC4(location);

		if (properties.eTag != null)
			delete.addHeader("If-Match", properties.eTag);

		@Cleanup CloseableHttpResponse response = httpClient.execute(delete, context);
		checkResponse(response);
	}


	/* helpers */

	protected void checkResponse(HttpResponse response) throws HttpException {
		checkResponse(response.getStatusLine());

		// handle Content-Location header (see RFC 4918 5.2 Collection Resources)
		Header contentLocationHdr = response.getFirstHeader("Content-Location");
		if (contentLocationHdr != null) {
			// Content-Location was set, update location correspondingly
			location = location.resolve(contentLocationHdr.getValue());
			Log.d(TAG, "Set Content-Location to " + location);
		}
	}

	protected static void checkResponse(StatusLine statusLine) throws HttpException {
		int code = statusLine.getStatusCode();

		if (code/100 == 1 || code/100 == 2)		// everything OK
			return;

		String reason = code + " " + statusLine.getReasonPhrase();
		switch (code) {
		case HttpStatus.SC_UNAUTHORIZED:
			throw new NotAuthorizedException(reason);
		case HttpStatus.SC_FORBIDDEN:
			throw new ForbiddenException(reason);
		case HttpStatus.SC_NOT_FOUND:
			throw new NotFoundException(reason);
		case HttpStatus.SC_CONFLICT:
			throw new ConflictException(reason);
		case HttpStatus.SC_PRECONDITION_FAILED:
			throw new PreconditionFailedException(reason);
		default:
			throw new HttpException(code, reason);
		}
	}

	/**
	 * Process a 207 Multi-status response as defined in RFC 4918 "13. Multi-Status Response"
	 */
	protected void processMultiStatus(HttpResponse response) throws IOException, HttpException, DavException {
		if (response.getStatusLine().getStatusCode() != HttpStatus.SC_MULTI_STATUS)
			throw new DavNoMultiStatusException();

		HttpEntity entity = response.getEntity();
		if (entity == null)
			throw new DavNoContentException();

		properties.contentType = ContentType.get(entity);
		@Cleanup InputStream content = entity.getContent();

		DavMultistatus multiStatus;
		try {
			Serializer serializer = new Persister();
			multiStatus = serializer.read(DavMultistatus.class, content, false);
		} catch (Exception ex) {
			throw new DavException("Couldn't parse Multi-Status response on REPORT multi-get", ex);
		}

		if (multiStatus.response == null)	// empty response
			return;

		// member list will be built from response
		List<WebDavResource> members = new LinkedList<>();

		// iterate through all resources (either ourselves or member)
		for (DavResponse singleResponse : multiStatus.response) {
			URI href;
			try {
				href = location.resolve(URIUtils.parseURI(singleResponse.href.href, false));
			} catch(Exception ex) {
				Log.w(TAG, "Ignoring illegal member URI in multi-status response", ex);
				continue;
			}
			Log.d(TAG, "Processing multi-status element: " + href);

			// process known properties
			Properties properties = new Properties();
			byte[] data = null;

			// in <response>, either <status> or <propstat> must be present
			if (singleResponse.status != null) {   // method 1 (status of resource as a whole)
				StatusLine status = BasicLineParserHC4.parseStatusLine(singleResponse.status, new BasicLineParserHC4());
				checkResponse(status);

			} else for (DavPropstat singlePropstat : singleResponse.propstat) {      // method 2 (propstat)
				StatusLine status = BasicLineParserHC4.parseStatusLine(singlePropstat.status, new BasicLineParserHC4());

				// ignore information about missing properties etc.
				if (status.getStatusCode()/100 != 1 && status.getStatusCode()/100 != 2)
					continue;

				DavProp prop = singlePropstat.prop;
				properties.process(prop);

				if (prop.calendarData != null && prop.calendarData.ical != null)
					data = prop.calendarData.ical.getBytes();
				else if (prop.addressData != null && prop.addressData.vcard != null)
					data = prop.addressData.vcard.getBytes();
			}

			// about which resource is this response?
			if (properties.isCollection)    // ensure trailing slashs for collections
				href = URIUtils.ensureTrailingSlash(href);

			if (location.equals(href) || URIUtils.ensureTrailingSlash(location).equals(href)) {	// about ourselves
				this.properties = properties;
				this.content = data;

			} else {						// about a member
				WebDavResource member = new WebDavResource(this, href);
				member.properties = properties;
				member.content = data;

				members.add(member);
			}
		}

		this.members = members;
	}


	public static class Properties {
		// DAV properties
		protected String
				currentUserPrincipal,
				addressBookHomeset,
				calendarHomeset,
				color;

		@Getter protected String
				displayName,
				description,
				timeZone,
				eTag,
				cTag;

		@Getter @Setter	protected ContentType contentType;

		@Getter protected boolean
				readOnly,
				isCollection,
				isCalendar,
				isAddressBook;

		@Getter protected List<String> supportedComponents;
		@Getter protected VCardVersion supportedVCardVersion;

		// fill from DavProp

		protected void process(DavProp prop) {
			if (prop.currentUserPrincipal != null && prop.currentUserPrincipal.getHref() != null)
				currentUserPrincipal = prop.currentUserPrincipal.getHref().href;

			if (prop.currentUserPrivilegeSet != null) {
				// privilege info available
				boolean mayAll = false,
						mayBind = false,
						mayUnbind = false,
						mayWrite = false,
						mayWriteContent = false;
				for (DavProp.Privilege privilege : prop.currentUserPrivilegeSet) {
					if (privilege.getAll() != null) mayAll = true;
					if (privilege.getBind() != null) mayBind = true;
					if (privilege.getUnbind() != null) mayUnbind = true;
					if (privilege.getWrite() != null) mayWrite = true;
					if (privilege.getWriteContent() != null) mayWriteContent = true;
				}
				if (!mayAll && !mayWrite && !(mayWriteContent && mayBind && mayUnbind))
					readOnly = true;
			}

			if (prop.addressbookHomeSet != null && prop.addressbookHomeSet.getHref() != null)
				addressBookHomeset = URIUtils.ensureTrailingSlash(prop.addressbookHomeSet.getHref().href);

			if (prop.calendarHomeSet != null && prop.calendarHomeSet.getHref() != null)
				calendarHomeset = URIUtils.ensureTrailingSlash(prop.calendarHomeSet.getHref().href);

			if (prop.displayname != null)
				displayName = prop.displayname.getDisplayName();

			if (prop.resourcetype != null) {
				if (prop.resourcetype.getCollection() != null)
					isCollection = true;
				if (prop.resourcetype.getAddressbook() != null) {	// CardDAV collection properties
					isAddressBook = true;

					if (prop.addressbookDescription != null)
						description = prop.addressbookDescription.getDescription();
					if (prop.supportedAddressData != null)
						for (DavProp.AddressDataType dataType : prop.supportedAddressData)
							if ("text/vcard".equalsIgnoreCase(dataType.getContentType()))
								// ignore "3.0" as it MUST be supported anyway
								if ("4.0".equals(dataType.getVersion()))
									supportedVCardVersion = VCardVersion.V4_0;
				}
				if (prop.resourcetype.getCalendar() != null) {		// CalDAV collection propertioes
					isCalendar = true;

					if (prop.calendarDescription != null)
						description = prop.calendarDescription.getDescription();

					if (prop.calendarColor != null)
						color = prop.calendarColor.getColor();

					if (prop.calendarTimezone != null)
						timeZone = prop.calendarTimezone.getTimezone();

					if (prop.supportedCalendarComponentSet != null) {
						supportedComponents = new LinkedList<>();
						for (Comp component : prop.supportedCalendarComponentSet)
							supportedComponents.add(component.getName());
					}
				}
			}

			if (prop.getctag != null)
				cTag = prop.getctag.getCTag();

			if (prop.getetag != null)
				eTag = prop.getetag.getETag();
		}

		// getters / setters

		public Integer getColor() {
			return color != null ? DAVUtils.CalDAVtoARGBColor(color) : null;
		}

		public URI getCurrentUserPrincipal() throws URISyntaxException {
			return currentUserPrincipal != null ? URIUtils.parseURI(currentUserPrincipal, false) : null;
		}

		public URI getAddressbookHomeSet() throws URISyntaxException {
			return addressBookHomeset != null ? URIUtils.parseURI(addressBookHomeset, false) : null;
		}

		public URI getCalendarHomeSet() throws URISyntaxException {
			return calendarHomeset != null ? URIUtils.parseURI(calendarHomeset, false) : null;
		}

		public String getTimeZone() {
			return timeZone != null ? iCalendar.TimezoneDefToTzId(timeZone) : null;
		}

		public void invalidateCTag() {
			cTag = null;
		}

	}
}
