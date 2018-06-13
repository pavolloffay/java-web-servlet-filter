package io.opentracing.contrib.web.servlet.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.regex.Pattern;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.ThreadLocalScopeManager;

/**
 * @author Pavol Loffay
 */
public abstract class AbstractJettyTest {

    // jetty starts on a random port
    private int serverPort;
    protected String contextPath = "/context";

    protected Server jettyServer;
    protected MockTracer mockTracer;

    @Before
    public void beforeTest() throws Exception {
        mockTracer = Mockito.spy(new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP));

        ServletContextHandler servletContext = new ServletContextHandler();
        servletContext.setContextPath(contextPath);
        servletContext.addServlet(TestServlet.class, "/hello");
        
        ServletHolder asyncServletHolder = new ServletHolder(new AsyncServlet(mockTracer));
        servletContext.addServlet(asyncServletHolder, "/async");
        asyncServletHolder.setAsyncSupported(true);
        servletContext.addServlet(AsyncImmediateExitServlet.class, "/asyncImmediateExit")
                .setAsyncSupported(true);

        servletContext.addServlet(new ServletHolder(new LocalSpanServlet(mockTracer)), "/localSpan");
        servletContext.addServlet(new ServletHolder(new CurrentSpanServlet(mockTracer)), "/currentSpan");
        ServletHolder servletHolder = servletContext
            .addServlet(ExceptionServlet.class, "/servletException");
        servletHolder.setAsyncSupported(true);

        servletContext.addFilter(new FilterHolder(tracingFilter()), "/*", EnumSet.of(DispatcherType.REQUEST,
                DispatcherType.FORWARD, DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.INCLUDE));
        servletContext.addFilter(ErrorFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        jettyServer = new Server(0);
        jettyServer.setHandler(servletContext);
        jettyServer.start();
        serverPort = ((ServerConnector)jettyServer.getConnectors()[0]).getLocalPort();
    }

    @After
    public void afterTest() throws Exception {
        jettyServer.stop();
        jettyServer.join();
    }

    protected Filter tracingFilter() {
        return new TracingFilter(mockTracer, Collections.singletonList(ServletFilterSpanDecorator.STANDARD_TAGS),
                Pattern.compile("/health"));
    }

    public String localRequestUrl(String path) {
        return "http://localhost:" + serverPort + ("/".equals(contextPath) ? "" : contextPath) + path;
    }

    public static class TestServlet extends HttpServlet {

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
        }
    }

    public static class LocalSpanServlet extends HttpServlet {

        private io.opentracing.Tracer tracer;

        public LocalSpanServlet(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            SpanContext spanContext = (SpanContext)request.getAttribute(TracingFilter.SERVER_SPAN_CONTEXT);
            tracer.buildSpan("localSpan")
                    .asChildOf(spanContext)
                    .startManual()
                    .finish();
        }
    }

    public static class CurrentSpanServlet extends HttpServlet {

        private io.opentracing.Tracer tracer;

        public CurrentSpanServlet(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            tracer.activeSpan().setTag("CurrentSpan", true);
        }
    }

    public static class ExceptionServlet extends HttpServlet {

        public static final String EXCEPTION_MESSAGE = ExceptionServlet.class.getName() + "message";

        @Override
        public void doGet(final HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
//            final AsyncContext asyncContext = request.startAsync();
//            asyncContext.start(new Runnable() {
//                @Override
//                public void run() {
//                    asyncContext.complete();
//                }
//            });
//            if (request.isAsyncStarted()) {
//                request.getAsyncContext().complete();
//            }
//            try {
                throw new ServletException(EXCEPTION_MESSAGE);
//            } finally {
//                if (request.isAsyncStarted()) {
//                    request.getAsyncContext().complete();
//                }
//            }

        }
    }

    public static class AsyncServlet extends HttpServlet {

        public static int ASYNC_SLEEP_TIME_MS = 250;

        private io.opentracing.Tracer tracer;

        public AsyncServlet(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public void doGet(final HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            // TODO: This could be avoided by using an OpenTracing aware Runnable (when available)
            final Span cont = tracer.activeSpan();

//            asyncContext.start(new Runnable() {
//                @Override
//                public void run() {
//                    HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
//                    try (Scope activeScope = tracer.scopeManager().activate(cont, false)) {
//                        try {
//                            Thread.sleep(ASYNC_SLEEP_TIME_MS);
//                            asyncResponse.setStatus(204);
//                            if (request.getQueryString() != null && request.getQueryString().contains("throwException")) {
////                                asyncContext.dispatch("/servletException?throwAsyncException");
////                                throw new RuntimeException();
//                            }
////                            if (request.getQueryString() != null && request.getQueryString().contains("throwNow")) {
////                                throw new RuntimeException();
////                            }
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                            asyncResponse.setStatus(500);
//                        } finally {
//                            asyncContext.complete();
//                        }
//                    }
//                }
//            });
//            try {
//                Thread.sleep(200);
            request.getRequestDispatcher("/servletException").forward(request, response);
//            final AsyncContext asyncContext = request.startAsync();
//            asyncContext.start(new Runnable() {
//                @Override
//                public void run() {
//                    try {
////                        request.getRequestDispatcher("/servletException").forward(request, response);
//                        asyncContext.dispatch("/servletException");
//                    } finally {
////                        asyncContext.complete();
//                    }
//                }
//            });
//                throw new RuntimeException();
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            } finally {
//                final AsyncContext asyncContext = request.startAsync(request, response);
//                asyncContext.complete();
//            }
        }
    }

    public static class AsyncImmediateExitServlet extends HttpServlet {

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            final AsyncContext asyncContext = request.startAsync(request, response);
            response.setStatus(204);
            asyncContext.complete();
        }
    }

    public static class ErrorFilter implements Filter {

        public static final String EXCEPTION_MESSAGE = ErrorFilter.class.getName() + "message";

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {}

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            HttpServletRequest httpServletRequest = (HttpServletRequest) request;

            if ("/filterException".equals(httpServletRequest.getServletPath())) {
                throw new RuntimeException(EXCEPTION_MESSAGE);
            }

            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {}
    }

}
