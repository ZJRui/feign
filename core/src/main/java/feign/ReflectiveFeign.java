/*
 * Copyright 2012-2022 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.template.UriUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

public class ReflectiveFeign extends Feign {

    private final ParseHandlersByName targetToHandlersByName;
    private final InvocationHandlerFactory factory;
    private final QueryMapEncoder queryMapEncoder;

    ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
                    QueryMapEncoder queryMapEncoder) {
        this.targetToHandlersByName = targetToHandlersByName;
        this.factory = factory;
        this.queryMapEncoder = queryMapEncoder;
    }

    /**
     * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
     * to cache the result.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T newInstance(Target<T> target) {
        /**
         *
         *
         * ReflectiveFeign的target方法会调用newInstance，从而target中指定的接口 创建代理对象
         *
         * 这个apply方法内部会使用 Contract，通过Contract 来解析 target对象中的type指定的class 中的 每一个方法上的注解
         *
         * 先前讲解Feign元数据时说过，对于的注解的解析是通过Contract类来实现的，可以看到对于每个方法都会生成一个MethodMetadata对象，
         * 接下来对MethodMetadata对象进行分析
         *  List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
         *
         *
         */
        Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
        Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
        List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

        for (Method method : target.type().getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            } else if (Util.isDefault(method)) {
                /**
                 *
                 *  比如接口中定义了default 类型的方法，如何为default类型的方法进行代理拦截？ 这就需要DefaultMethodHandler。
                 *  上面的 nameToHandler 只是针对 接口中 抽象方法吧，没有处理 接口中的default方法。
                 *
                 * https://blog.jooq.org/correct-reflective-access-to-interface-default-methods-in-java-8-9-10/
                 */

                DefaultMethodHandler handler = new DefaultMethodHandler(method);
                defaultMethodHandlers.add(handler);
                methodToHandler.put(method, handler);
            } else {
                methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
            }
        }
        /**
         * 创建一个InvocationHandler，在这个InvocationHandler内部 拦截到当前执行的接口的方法，然后分析接口
         * 方法上的注解，然后发送http请求。
         */
        InvocationHandler handler = factory.create(target, methodToHandler);
        /**
         * 为接口创建代理对象
         *
         * 请求是怎么转到 Feign 的？
         * 分为两部分，第一是为接口定义的每个接口都生成一个实现方法，结果就是 SynchronousMethodHandler 对象。
         * 第二是为该服务接口生成了动态代理。动态代理的实现是 ReflectiveFeign.FeignInvocationHanlder，代理被调用的时候，会
         * 根据当前调用的方法，转到对应的 SynchronousMethodHandler。
         */
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
                new Class<?>[]{target.type()}, handler);

        for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
            defaultMethodHandler.bindTo(proxy);
        }
        return proxy;
    }

    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, MethodHandler> dispatch;

        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler =
                            args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            }

            //找到具体的 method 的 Handler，然后调用 invoke 方法。这样就又进入了SynchronousMethodHandler对象的 invoke 方法。
            return dispatch.get(method).invoke(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignInvocationHandler) {
                FeignInvocationHandler other = (FeignInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }

    static final class ParseHandlersByName {

        private final Contract contract;
        private final Options options;
        private final Encoder encoder;
        private final Decoder decoder;
        private final ErrorDecoder errorDecoder;
        private final QueryMapEncoder queryMapEncoder;
        private final SynchronousMethodHandler.Factory factory;

        ParseHandlersByName(
                Contract contract,
                Options options,
                Encoder encoder,
                Decoder decoder,
                QueryMapEncoder queryMapEncoder,
                ErrorDecoder errorDecoder,
                SynchronousMethodHandler.Factory factory) {
            this.contract = contract;
            this.options = options;
            this.factory = factory;
            this.errorDecoder = errorDecoder;
            this.queryMapEncoder = queryMapEncoder;
            this.encoder = checkNotNull(encoder, "encoder");
            this.decoder = checkNotNull(decoder, "decoder");
        }

        public Map<String, MethodHandler> apply(Target target) {
            /**
             * 解析指定接口 中的所有方法，获取方法上的注解
             */
            List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
            Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
            for (MethodMetadata md : metadata) {
                BuildTemplateByResolvingArgs buildTemplate;
                /**
                 * 上面我们已经获取到了一个MethodMetadata列表，这里仅仅包含了方法的一些基本信息。还未涉及到编码器，
                 * 所以Feign提供了一个RequestTemplate.Factory工厂类，通过请求参数的类型，Feign为我们提供不同的模板工厂类。
                 *
                 * BuildTemplateByResolvingArgs 是Factory接口的实现，
                 *   //根据目标接口类和方法上的注解信息判断该用哪种 buildTemplate
                 *
                 */
                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
                    buildTemplate =
                            new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else if (md.bodyIndex() != null || md.alwaysEncodeBody()) {
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else {
                    buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
                }
                if (md.isIgnored()) {
                    result.put(md.configKey(), args -> {
                        throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
                    });
                } else {
                    /**
                     * 下面的factory是 SynchronousMethodHandler.Factory
                     *
                     * 前面都是封装了方法的基本参数，我们在发起调用时还需要Client客户端等参数，Feign提供了MethodHandler作为方法请求的执行器。
                     *
                     * factory.create方法中会创建 SynchronousMethodHandler
                     *
                     *   //调用synchronousMethodHandlerFactory来生成SynchronousMethodHandler对象。这个就是对接口某个方法的实现。
                     *
                     */
                    result.put(md.configKey(),
                            factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
                }
            }
            /**
             * 那么这个ParseHandlersByName类其实就类似于一个工具类，
             * 他的apply方法里面调用了contract接口的解析获得了MethodMetadata列表，然后在将方法包装成MethodHandler，最后根据configKey返回一个Map
             *
             */
            return result;
        }
    }

    private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

        private final QueryMapEncoder queryMapEncoder;

        protected final MethodMetadata metadata;
        protected final Target<?> target;
        private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

        private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder,
                                             Target target) {
            this.metadata = metadata;
            this.target = target;
            this.queryMapEncoder = queryMapEncoder;
            if (metadata.indexToExpander() != null) {
                indexToExpander.putAll(metadata.indexToExpander());
                return;
            }
            if (metadata.indexToExpanderClass().isEmpty()) {
                return;
            }
            for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
                    .indexToExpanderClass().entrySet()) {
                try {
                    indexToExpander
                            .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalStateException(e);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public RequestTemplate create(Object[] argv) {
            RequestTemplate mutable = RequestTemplate.from(metadata.template());
            mutable.feignTarget(target);
            if (metadata.urlIndex() != null) {
                int urlIndex = metadata.urlIndex();
                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
                mutable.target(String.valueOf(argv[urlIndex]));
            }
            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
            for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
                int i = entry.getKey();
                Object value = argv[entry.getKey()];
                if (value != null) { // Null values are skipped.
                    if (indexToExpander.containsKey(i)) {
                        value = expandElements(indexToExpander.get(i), value);
                    }
                    for (String name : entry.getValue()) {
                        varBuilder.put(name, value);
                    }
                }
            }

            RequestTemplate template = resolve(argv, mutable, varBuilder);
            if (metadata.queryMapIndex() != null) {
                // add query map parameters after initial resolve so that they take
                // precedence over any predefined values
                Object value = argv[metadata.queryMapIndex()];
                Map<String, Object> queryMap = toQueryMap(value);
                template = addQueryMapQueryParameters(queryMap, template);
            }

            if (metadata.headerMapIndex() != null) {
                // add header map parameters for a resolution of the user pojo object
                Object value = argv[metadata.headerMapIndex()];
                Map<String, Object> headerMap = toQueryMap(value);
                template = addHeaderMapHeaders(headerMap, template);
            }

            return template;
        }

        private Map<String, Object> toQueryMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            try {
                return queryMapEncoder.encode(value);
            } catch (EncodeException e) {
                throw new IllegalStateException(e);
            }
        }

        private Object expandElements(Expander expander, Object value) {
            if (value instanceof Iterable) {
                return expandIterable(expander, (Iterable) value);
            }
            return expander.expand(value);
        }

        private List<String> expandIterable(Expander expander, Iterable value) {
            List<String> values = new ArrayList<String>();
            for (Object element : value) {
                if (element != null) {
                    values.add(expander.expand(element));
                }
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                    RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : headerMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null : nextObject.toString());
                    }
                } else {
                    values.add(currValue == null ? null : currValue.toString());
                }

                mutable.header(currEntry.getKey(), values);
            }
            return mutable;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                           RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : queryMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null : UriUtils.encode(nextObject.toString()));
                    }
                } else if (currValue instanceof Object[]) {
                    for (Object value : (Object[]) currValue) {
                        values.add(value == null ? null : UriUtils.encode(value.toString()));
                    }
                } else {
                    if (currValue != null) {
                        values.add(UriUtils.encode(currValue.toString()));
                    }
                }

                if (values.size() > 0) {
                    mutable.query(UriUtils.encode(currEntry.getKey()), values);
                }
            }
            return mutable;
        }

        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            return mutable.resolve(variables);
        }
    }

    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                                 QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
            for (Entry<String, Object> entry : variables.entrySet()) {
                if (metadata.formParams().contains(entry.getKey())) {
                    formVariables.put(entry.getKey(), entry.getValue());
                }
            }
            try {
                encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }

    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                             QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {

            boolean alwaysEncodeBody = mutable.methodMetadata().alwaysEncodeBody();

            Object body = null;
            if (!alwaysEncodeBody) {
                body = argv[metadata.bodyIndex()];
                checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
            }

            try {
                if (alwaysEncodeBody) {
                    body = argv == null ? new Object[0] : argv;
                    encoder.encode(body, Object[].class, mutable);
                } else {
                    encoder.encode(body, metadata.bodyType(), mutable);
                }
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }
}
