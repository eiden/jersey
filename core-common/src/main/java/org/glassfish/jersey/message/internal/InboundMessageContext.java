/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.message.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.MessageProcessingException;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;

import javax.xml.transform.Source;

import org.glassfish.jersey.internal.LocalizationMessages;
import org.glassfish.jersey.internal.ProcessingException;
import org.glassfish.jersey.internal.PropertiesDelegate;
import org.glassfish.jersey.message.MessageBodyWorkers;

import com.google.common.base.Function;
import com.google.common.collect.Sets;

/**
 * Base inbound message context implementation.
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public class InboundMessageContext {
    private static final Logger LOGGER = Logger.getLogger(InboundMessageContext.class.getName());
    private static final InputStream EMPTY = new InputStream() {

        @Override
        public int read() throws IOException {
            return -1;
        }
    };
    private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

    private final MultivaluedMap<String, String> headers;
    private final ContentStream contentStream;
    private MessageBodyWorkers workers;

    /**
     * Input stream and its state. State is represented by the {@link Type Type enum} and
     * is used to control the execution of interceptors.
     */
    private static class ContentStream {
        private InputStream entityStream;
        private boolean interceptable;
        private boolean buffered;
        private boolean closed;

        ContentStream(InputStream entityStream) {
            super();
            this.entityStream = entityStream;
            this.interceptable = this.buffered = this.closed = false;
        }

        void setBufferedContentStream(InputStream bufferedInputStream) {
            entityStream = bufferedInputStream;
            buffered = true;
            closed = false;
        }

        void setExternalContentStream(InputStream stream) {
            entityStream = stream;
            interceptable = true;
            closed = false;
        }

        InputStream getInputStream() {
            return entityStream;
        }

        boolean isInterceptable() {
            return interceptable;
        }

        boolean isBuffered() {
            return buffered;
        }

        void reset() {
            try {
                entityStream.reset();
            } catch (IOException ex) {
                throw new MessageProcessingException(LocalizationMessages.MESSAGE_CONTENT_BUFFER_RESET_FAILED(), ex);
            }
        }

        void close() {
            if (!closed && entityStream != null) {
                try {
                    entityStream.close();
                } catch (IOException ex) {
                    throw new MessageProcessingException(LocalizationMessages.MESSAGE_CONTENT_INPUT_STREAM_CLOSE_FAILED(), ex);
                } finally {
                    closed = true;
                    buffered = false;
                    entityStream = null;
                }
            }
        }

        public boolean isEmpty() {
            if (closed) {
                throw new IllegalStateException(LocalizationMessages.ERROR_ENTITY_STREAM_CLOSED());
            }
            if (entityStream == null) {
                return true;
            }
            try {
                if (entityStream.available() > 0) {
                    return false;
                } else if (entityStream.markSupported()) {
                    entityStream.mark(1);
                    int i = entityStream.read();
                    entityStream.reset();
                    return i == -1;
                } else {
                    int b = entityStream.read();
                    if (b == -1) {
                        return true;
                    }

                    PushbackInputStream pbis;
                    if (entityStream instanceof PushbackInputStream) {
                        pbis = (PushbackInputStream) entityStream;
                    } else {
                        pbis = new PushbackInputStream(entityStream, 1);
                        entityStream = pbis;
                    }
                    pbis.unread(b);

                    return false;
                }
            } catch (IOException ex) {
                throw new ProcessingException(ex);
            }
        }
    }

    /**
     * Create new inbound message context.
     */
    public InboundMessageContext() {
        this.headers = HeadersFactory.createInbound();
        this.contentStream = new ContentStream(EMPTY);
    }

    // Message headers

    /**
     * Add a new header value.
     *
     * @param name  header name.
     * @param value header value.
     * @return updated context.
     */
    public InboundMessageContext header(String name, Object value) {
        getHeaders().add(name, HeadersFactory.asString(value, RuntimeDelegate.getInstance()));
        return this;
    }

    /**
     * Add new header values.
     *
     * @param name   header name.
     * @param values header values.
     * @return updated context.
     */
    public InboundMessageContext headers(String name, Object... values) {
        this.getHeaders().addAll(name, HeadersFactory.asStringList(Arrays.asList(values), RuntimeDelegate.getInstance()));
        return this;
    }

    /**
     * Add new header values.
     *
     * @param name   header name.
     * @param values header values.
     * @return updated context.
     */
    public InboundMessageContext headers(String name, Iterable<?> values) {
        this.getHeaders().addAll(name, iterableToList(values));
        return this;
    }

    /**
     * Add new headers.
     *
     * @param headers new headers.
     * @return updated context.
     */
    public InboundMessageContext headers(MultivaluedMap<String, String> headers) {
        this.getHeaders().putAll(headers);
        return this;
    }

    /**
     * Add new headers.
     *
     * @param headers new headers.
     * @return updated context.
     */
    public InboundMessageContext headers(Map<String, List<String>> headers) {
        this.getHeaders().putAll(headers);
        return this;
    }

    /**
     * Remove a header.
     *
     * @param name header name.
     * @return updated context.
     */
    public InboundMessageContext remove(String name) {
        this.getHeaders().remove(name);
        return this;
    }

    private static List<String> iterableToList(final Iterable<?> values) {
        final LinkedList<String> linkedList = new LinkedList<String>();

        final RuntimeDelegate rd = RuntimeDelegate.getInstance();
        for (Object element : values) {
            linkedList.add(HeadersFactory.asString(element, rd));
        }

        return linkedList;
    }

    /**
     * Get a message header as a single string value.
     *
     * Each single header value is converted to String using a
     * {@link javax.ws.rs.ext.RuntimeDelegate.HeaderDelegate} if one is available
     * via {@link javax.ws.rs.ext.RuntimeDelegate#createHeaderDelegate(java.lang.Class)}
     * for the header value class or using its {@code toString} method  if a header
     * delegate is not available.
     *
     * @param name the message header.
     * @return the message header value. If the message header is not present then
     *         {@code null} is returned. If the message header is present but has no
     *         value then the empty string is returned. If the message header is present
     *         more than once then the values of joined together and separated by a ','
     *         character.
     */
    public String getHeaderString(String name) {
        List<String> values = this.headers.get(name);
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            return "";
        }

        final Iterator<String> valuesIterator = values.iterator();
        StringBuilder buffer = new StringBuilder(valuesIterator.next());
        while (valuesIterator.hasNext()) {
            buffer.append(',').append(valuesIterator.next());
        }

        return buffer.toString();
    }

    /**
     * Get a single typed header value.
     *
     * @param name        header name.
     * @param converter   from string conversion function. Is expected to throw {@link org.glassfish.jersey.internal.ProcessingException}
     *                    if conversion fails.
     * @param convertNull if {@code true} this method calls the provided converter even for {@code null}. Otherwise this
     *                    method returns the {@code null} without calling the converter.
     * @return value of the header, or (possibly converted) {@code null} if not present.
     */
    private <T> T singleHeader(String name, Function<String, T> converter, boolean convertNull) {
        final List<String> values = this.headers.get(name);

        if (values == null || values.isEmpty()) {
            return convertNull ? converter.apply(null) : null;
        }
        if (values.size() > 1) {
            throw new HeaderValueException(LocalizationMessages.TOO_MANY_HEADER_VALUES(name, values.toString()));
        }

        Object value = values.get(0);
        if (value == null) {
            return convertNull ? converter.apply(null) : null;
        }

        try {
            return converter.apply(HeadersFactory.asString(value, null));
        } catch (ProcessingException ex) {
            throw exception(name, value, ex);
        }
    }

    private static HeaderValueException exception(final String headerName, Object headerValue, Exception e) {
        return new HeaderValueException(LocalizationMessages.UNABLE_TO_PARSE_HEADER_VALUE(headerName, headerValue), e);
    }

    /**
     * Get the mutable message headers multivalued map.
     *
     * @return mutable multivalued map of message headers.
     */
    public MultivaluedMap<String, String> getHeaders() {
        return this.headers;
    }

    /**
     * Get message date.
     *
     * @return the message date, otherwise {@code null} if not present.
     */
    public Date getDate() {
        return singleHeader(HttpHeaders.DATE, new Function<String, Date>() {
            @Override
            public Date apply(String input) {
                try {
                    return HttpHeaderReader.readDate(input);
                } catch (ParseException ex) {
                    throw new ProcessingException(ex);
                }
            }
        }, false);
    }

    /**
     * Get If-Match header.
     *
     * @return the If-Match header value, otherwise {@code null} if not present.
     */
    public Set<MatchingEntityTag> getIfMatch() {
        final String ifMatch = getHeaderString(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.length() == 0) {
            return null;
        }
        try {
            return HttpHeaderReader.readMatchingEntityTag(ifMatch);
        } catch (java.text.ParseException e) {
            throw exception(HttpHeaders.IF_MATCH, ifMatch, e);
        }
    }

    /**
     * Get If-None-Match header.
     *
     * @return the If-None-Match header value, otherwise {@code null} if not present.
     */
    public Set<MatchingEntityTag> getIfNoneMatch() {
        final String ifNoneMatch = getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch == null || ifNoneMatch.length() == 0) {
            return null;
        }
        try {
            return HttpHeaderReader.readMatchingEntityTag(ifNoneMatch);
        } catch (java.text.ParseException e) {
            throw exception(HttpHeaders.IF_NONE_MATCH, ifNoneMatch, e);
        }
    }

    /**
     * Get the language of the entity.
     *
     * @return the language of the entity or {@code null} if not specified
     */
    public Locale getLanguage() {
        return singleHeader(HttpHeaders.CONTENT_LANGUAGE, new Function<String, Locale>() {
            @Override
            public Locale apply(String input) {
                try {
                    return new LanguageTag(input).getAsLocale();
                } catch (ParseException e) {
                    throw new ProcessingException(e);
                }
            }
        }, false);
    }

    /**
     * Get Content-Length value.
     *
     * @return Content-Length as integer if present and valid number. In other
     *         cases returns -1.
     */
    public int getLength() {
        return singleHeader(HttpHeaders.CONTENT_LENGTH, new Function<String, Integer>() {
            @Override
            public Integer apply(String input) {
                try {
                    return (input != null && input.length() > 0) ? Integer.parseInt(input) : -1;
                } catch (NumberFormatException ex) {
                    throw new ProcessingException(ex);
                }
            }
        }, true);
    }

    /**
     * Get the media type of the entity.
     *
     * @return the media type or {@code null} if not specified (e.g. there's no
     *         message entity).
     */
    public MediaType getMediaType() {
        return singleHeader(HttpHeaders.CONTENT_TYPE, new Function<String, MediaType>() {
            @Override
            public MediaType apply(String input) {
                try {
                    return MediaType.valueOf(input);
                } catch (IllegalArgumentException iae) {
                    throw new ProcessingException(iae);
                }
            }
        }, false);
    }

    /**
     * Get a list of media types that are acceptable for a request.
     *
     * @return a read-only list of requested response media types sorted according
     *         to their q-value, with highest preference first.
     */
    public List<AcceptableMediaType> getQualifiedAcceptableMediaTypes() {
        final String value = getHeaderString(HttpHeaders.ACCEPT);

        if (value == null || value.length() == 0) {
            return Collections.unmodifiableList(MediaTypes.GENERAL_ACCEPT_MEDIA_TYPE_LIST);
        }

        try {
            return Collections.unmodifiableList(HttpHeaderReader.readAcceptMediaType(value));
        } catch (ParseException e) {
            throw exception(HttpHeaders.ACCEPT, value, e);
        }
    }

    /**
     * Get a list of languages that are acceptable for the message.
     *
     * @return a read-only list of acceptable languages sorted according
     *         to their q-value, with highest preference first.
     */
    public List<AcceptableLanguageTag> getQualifiedAcceptableLanguages() {
        final String value = getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        if (value == null || value.length() == 0) {
            return Collections.singletonList(new AcceptableLanguageTag("*", null));
        }

        try {
            return Collections.unmodifiableList(HttpHeaderReader.readAcceptLanguage(value));
        } catch (ParseException e) {
            throw exception(HttpHeaders.ACCEPT_LANGUAGE, value, e);
        }
    }

    /**
     * Get the list of language tag from the "Accept-Charset" of an HTTP request.
     *
     * @return The list of AcceptableToken. This list
     *         is ordered with the highest quality acceptable charset occurring first.
     */
    public List<AcceptableToken> getQualifiedAcceptCharset() {
        final String acceptCharset = getHeaderString(HttpHeaders.ACCEPT_CHARSET);
        try {
            if (acceptCharset == null || acceptCharset.length() == 0) {
                return Collections.singletonList(new AcceptableToken("*"));
            }
            return HttpHeaderReader.readAcceptToken(acceptCharset);
        } catch (java.text.ParseException e) {
            throw exception(HttpHeaders.ACCEPT_CHARSET, acceptCharset, e);
        }
    }

    /**
     * Get the list of language tag from the "Accept-Charset" of an HTTP request.
     *
     * @return The list of AcceptableToken. This list
     *         is ordered with the highest quality acceptable charset occurring first.
     */
    public List<AcceptableToken> getQualifiedAcceptEncoding() {
        final String acceptEncoding = getHeaderString(HttpHeaders.ACCEPT_ENCODING);
        try {
            if (acceptEncoding == null || acceptEncoding.length() == 0) {
                return Collections.singletonList(new AcceptableToken("*"));
            }
            return HttpHeaderReader.readAcceptToken(acceptEncoding);
        } catch (java.text.ParseException e) {
            throw exception("Accept-Encoding", acceptEncoding, e);
        }
    }

    /**
     * Get any cookies that accompanied the request.
     *
     * @return a read-only map of cookie name (String) to {@link javax.ws.rs.core.Cookie}.
     */
    public Map<String, Cookie> getRequestCookies() {
        List<String> cookies = this.headers.get(HttpHeaders.COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Cookie> result = new HashMap<String, Cookie>();
        for (String cookie : cookies) {
            if (cookie != null) {
                result.putAll(HttpHeaderReader.readCookies(cookie));
            }
        }
        return result;
    }

    /**
     * Get the allowed HTTP methods from the Allow HTTP header.
     *
     * @return the allowed HTTP methods, all methods will returned as upper case
     *         strings.
     */
    public Set<String> getAllowedMethods() {
        final String allowed = getHeaderString(HttpHeaders.ALLOW);
        if (allowed == null || allowed.length() == 0) {
            return Collections.emptySet();
        }
        try {
            return new HashSet<String>(HttpHeaderReader.readStringList(allowed.toUpperCase()));
        } catch (java.text.ParseException e) {
            throw exception(HttpHeaders.ALLOW, allowed, e);
        }
    }

    /**
     * Get any new cookies set on the response message.
     *
     * @return a read-only map of cookie name (String) to a {@link javax.ws.rs.core.NewCookie new cookie}.
     */
    public Map<String, NewCookie> getResponseCookies() {
        List<String> cookies = this.headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, NewCookie> result = new HashMap<String, NewCookie>();
        for (String cookie : cookies) {
            if (cookie != null) {
                NewCookie newCookie = HttpHeaderReader.readNewCookie(cookie);
                result.put(newCookie.getName(), newCookie);
            }
        }
        return result;
    }

    /**
     * Get the entity tag.
     *
     * @return the entity tag, otherwise {@code null} if not present.
     */
    public EntityTag getEntityTag() {
        return singleHeader(HttpHeaders.ETAG, new Function<String, EntityTag>() {
            @Override
            public EntityTag apply(String value) {
                return EntityTag.valueOf(value);
            }
        }, false);
    }

    /**
     * Get the last modified date.
     *
     * @return the last modified date, otherwise {@code null} if not present.
     */
    public Date getLastModified() {
        return singleHeader(HttpHeaders.LAST_MODIFIED, new Function<String, Date>() {
            @Override
            public Date apply(String input) {
                try {
                    return HttpHeaderReader.readDate(input);
                } catch (ParseException e) {
                    throw new ProcessingException(e);
                }
            }
        }, false);
    }

    /**
     * Get the location.
     *
     * @return the location URI, otherwise {@code null} if not present.
     */
    public URI getLocation() {
        return singleHeader(HttpHeaders.LOCATION, new Function<String, URI>() {
            @Override
            public URI apply(String value) {
                try {
                    return URI.create(value);
                } catch (IllegalArgumentException ex) {
                    throw new ProcessingException(ex);
                }
            }
        }, false);
    }

    /**
     * Get the links attached to the message as header.
     *
     * @return links, may return empty {@link java.util.Set} if no links are present. Never
     *         returns {@code null}.
     */
    public Set<Link> getLinks() {
        List<String> links = this.headers.get(HttpHeaders.LINK);
        if (links == null || links.isEmpty()) {
            return Collections.emptySet();
        }

        try {
            Set<Link> result = new HashSet<Link>(links.size());
            for (String l : links) {
                result.add(Link.valueOf(l));
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw exception(HttpHeaders.LINK, links, e);
        }
    }

    /**
     * Check if link for relation exists.
     *
     * @param relation link relation.
     * @return {@code true} if the for the relation link exists, {@code false}
     *         otherwise.
     */
    public boolean hasLink(String relation) {
        for (Link link : getLinks()) {
            List<String> relations = LinkProvider.getLinkRelations(link.getRel());

            if (relations != null && relations.contains(relation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the link for the relation.
     *
     * @param relation link relation.
     * @return the link for the relation, otherwise {@code null} if not present.
     */
    public Link getLink(String relation) {
        for (Link link : getLinks()) {
            List<String> relations = LinkProvider.getLinkRelations(link.getRel());
            if (relations != null && relations.contains(relation)) {
                return link;
            }
        }
        return null;
    }

    /**
     * Convenience method that returns a {@link javax.ws.rs.core.Link.Builder Link.Builder}
     * for the relation.
     *
     * @param relation link relation.
     * @return the link builder for the relation, otherwise {@code null} if not
     *         present.
     */
    public Link.Builder getLinkBuilder(String relation) {
        Link link = getLink(relation);
        if (link == null) {
            return null;
        }

        return Link.fromLink(link);
    }

    // Message entity

    /**
     * Get context message body workers.
     *
     * @return context message body workers.
     */
    public MessageBodyWorkers getWorkers() {
        return workers;
    }

    /**
     * Set context message body workers.
     *
     * @param workers context message body workers.
     */
    public void setWorkers(MessageBodyWorkers workers) {
        this.workers = workers;
    }

    /**
     * Check if there is a non-empty entity input stream is available in the
     * message.
     *
     * The method returns {@code true} if the entity is present, returns
     * {@code false} otherwise.
     *
     * @return {@code true} if there is an entity present in the message,
     *         {@code false} otherwise.
     */
    public boolean hasEntity() {
        try {
            return !contentStream.isEmpty();
        } catch (IllegalStateException ex) {
            // input stream has been closed.
            return false;
        }
    }

    /**
     * Get the entity input stream.
     *
     * @return entity input stream.
     */
    public InputStream getEntityStream() {
        return contentStream.getInputStream();
    }

    /**
     * Set a new entity input stream.
     *
     * @param input new entity input stream.
     */
    public void setEntityStream(InputStream input) {
        this.contentStream.setExternalContentStream(input);
    }

    /**
     * Read entity from a context entity input stream.
     *
     * @param <T>                entity Java object type.
     * @param rawType            raw Java entity type.
     * @param propertiesDelegate request-scoped properties delegate.
     * @return entity read from a context entity input stream.
     */
    public <T> T readEntity(Class<T> rawType, PropertiesDelegate propertiesDelegate) {
        return readEntity(rawType, rawType, EMPTY_ANNOTATIONS, propertiesDelegate);
    }


    /**
     * Read entity from a context entity input stream.
     *
     * @param <T>                entity Java object type.
     * @param rawType            raw Java entity type.
     * @param annotations        entity annotations.
     * @param propertiesDelegate request-scoped properties delegate.
     * @return entity read from a context entity input stream.
     */
    public <T> T readEntity(Class<T> rawType, Annotation[] annotations, PropertiesDelegate propertiesDelegate) {
        return readEntity(rawType, rawType, annotations, propertiesDelegate);
    }

    /**
     * Read entity from a context entity input stream.
     *
     * @param <T>                entity Java object type.
     * @param rawType            raw Java entity type.
     * @param type               generic Java entity type.
     * @param propertiesDelegate request-scoped properties delegate.
     * @return entity read from a context entity input stream.
     */
    public <T> T readEntity(Class<T> rawType, Type type, PropertiesDelegate propertiesDelegate) {
        return readEntity(rawType, type, EMPTY_ANNOTATIONS, propertiesDelegate);
    }

    /**
     * Read entity from a context entity input stream.
     *
     * @param <T>                entity Java object type.
     * @param rawType            raw Java entity type.
     * @param type               generic Java entity type.
     * @param annotations        entity annotations.
     * @param propertiesDelegate request-scoped properties delegate.
     * @return entity read from a context entity input stream.
     */
    @SuppressWarnings("unchecked")
    public <T> T readEntity(Class<T> rawType, Type type, Annotation[] annotations, PropertiesDelegate propertiesDelegate) {
        if (contentStream.isBuffered()) {
            contentStream.reset();
        }

        if (contentStream.isEmpty()) {
            return null;
        }

        if (workers == null) {
            return null;
        }

        try {
            T t = (T) workers.readFrom(
                    rawType,
                    type,
                    annotations,
                    getMediaType(),
                    headers,
                    propertiesDelegate,
                    contentStream.getInputStream(),
                    contentStream.isInterceptable());

            if (!contentStream.isBuffered() && !(t instanceof Closeable) && !(t instanceof Source)) {
                contentStream.close();
            }
            return t;
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, LocalizationMessages.ERROR_READING_ENTITY_FROM_INPUT_STREAM(), ex);
        }
        return null;
    }

    /**
     * Buffer the entity stream (if not empty).
     *
     * @return {@code true} if the entity input stream was successfully buffered.
     * @throws MessageProcessingException in case of an IO error.
     */
    public boolean bufferEntity() throws MessageProcessingException {
        try {
            if (contentStream.isBuffered()) {
                return true;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ReaderWriter.writeTo(contentStream.getInputStream(), baos);
            } finally {
                contentStream.close();
            }

            contentStream.setBufferedContentStream(new ByteArrayInputStream(baos.toByteArray()));

            return true;
        } catch (IOException ex) {
            throw new MessageProcessingException(LocalizationMessages.MESSAGE_CONTENT_BUFFERING_FAILED(), ex);
        }
    }

    /**
     * Closes the underlying content stream.
     */
    public void close() {
        contentStream.close();
    }
}
