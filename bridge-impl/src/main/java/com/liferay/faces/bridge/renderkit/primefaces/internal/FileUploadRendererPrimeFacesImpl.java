/**
 * Copyright (c) 2000-2018 Liferay, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liferay.faces.bridge.renderkit.primefaces.internal;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.event.FacesEvent;
import javax.faces.render.Renderer;
import javax.faces.render.RendererWrapper;
import javax.portlet.PortletContext;
import javax.portlet.PortletRequest;
import javax.portlet.faces.BridgeFactoryFinder;
import javax.servlet.http.Part;

import com.liferay.faces.bridge.component.inputfile.internal.HtmlInputFilePartImpl;
import com.liferay.faces.bridge.context.map.internal.ContextMapFactory;
import com.liferay.faces.bridge.model.UploadedFile;
import com.liferay.faces.util.logging.Logger;
import com.liferay.faces.util.logging.LoggerFactory;
import com.liferay.faces.util.product.Product;
import com.liferay.faces.util.product.ProductFactory;


/**
 * This class is a runtime wrapper around the PrimeFaces FileUploadRenderer class that makes the p:fileUpload component
 * compatible with a portlet environment.
 *
 * @author  Neil Griffin
 */
public class FileUploadRendererPrimeFacesImpl extends RendererWrapper {

	// Logger
	private static final Logger logger = LoggerFactory.getLogger(FileUploadRendererPrimeFacesImpl.class);

	// Private Constants
	private static final String FQCN_FILE_UPLOAD = "org.primefaces.component.fileupload.FileUpload";
	private static final String FQCN_FILE_UPLOAD_EVENT = "org.primefaces.event.FileUploadEvent";
	private static final String FQCN_NATIVE_UPLOADED_FILE = "org.primefaces.model.NativeUploadedFile";
	private static final String FQCN_UPLOADED_FILE = "org.primefaces.model.UploadedFile";

	// Private Data Members
	private Renderer wrappedRenderer;

	public FileUploadRendererPrimeFacesImpl(Renderer renderer) {
		this.wrappedRenderer = renderer;
	}

	/* package-private */ static boolean isSimpleMode(UIComponent fileUpload) {

		Map<String, Object> attributes = fileUpload.getAttributes();

		return isSimpleMode(attributes);
	}

	private static boolean isSimpleMode(Map<String, Object> fileUploadAttributes) {

		Object mode = fileUploadAttributes.get("mode");

		return "simple".equals(mode);
	}

	/**
	 * This method overrides the {@link RendererWrapper#decode(FacesContext, UIComponent)} method so that it can avoid a
	 * Servlet-API dependency in the PrimeFaces FileUploadRenderer. Note that p:fileUpload will do an Ajax postback and
	 * invoke the JSF lifecycle for each individual file.
	 */
	@Override
	public void decode(FacesContext facesContext, UIComponent uiComponent) {

		Map<String, Object> attributes = uiComponent.getAttributes();
		Object disabled = attributes.get("disabled");

		if (!Boolean.TRUE.equals(disabled)) {

			try {
				String clientId = uiComponent.getClientId(facesContext);
				ExternalContext externalContext = facesContext.getExternalContext();
				Map<String, String> requestParameterMap = externalContext.getRequestParameterMap();
				String submittedValue = requestParameterMap.get(clientId);

				if (submittedValue == null) {

					Object skinSimple = attributes.get("skinSimple");

					if (isSimpleMode(attributes) && Boolean.TRUE.equals(skinSimple)) {

						clientId += "_input";
						submittedValue = requestParameterMap.get(clientId);
					}
				}

				if (submittedValue != null) {

					// Get the UploadedFile from the request attribute map.
					PortletContext portletContext = (PortletContext) externalContext.getContext();
					ContextMapFactory contextMapFactory = (ContextMapFactory) BridgeFactoryFinder.getFactory(
							portletContext, ContextMapFactory.class);
					PortletRequest portletRequest = (PortletRequest) externalContext.getRequest();
					Map<String, List<UploadedFile>> uploadedFileMap = contextMapFactory.getUploadedFileMap(
							portletRequest);

					List<UploadedFile> uploadedFiles = uploadedFileMap.get(clientId);

					if (uploadedFiles != null) {

						for (UploadedFile uploadedFile : uploadedFiles) {

							// Convert the UploadedFile to a Commons-FileUpload FileItem.
							Part part = new HtmlInputFilePartImpl(uploadedFile, clientId);

							// Reflectively create an instance of the PrimeFaces DefaultUploadedFile class.
							final Product PRIMEFACES = ProductFactory.getProduct(Product.Name.PRIMEFACES);
							Object defaultUploadedFile;
							Class<?> defaultUploadedFileClass = Class.forName(FQCN_NATIVE_UPLOADED_FILE);

							if ((PRIMEFACES.getMajorVersion() > 6) ||
									((PRIMEFACES.getMajorVersion() == 6) && (PRIMEFACES.getMinorVersion() >= 2))) {

								Class<?> fileUploadClass = Class.forName(FQCN_FILE_UPLOAD);
								Constructor<?> constructor = defaultUploadedFileClass.getDeclaredConstructor(Part.class,
										fileUploadClass);
								defaultUploadedFile = constructor.newInstance(part, uiComponent);
							}
							else {

								Constructor<?> constructor = defaultUploadedFileClass.getDeclaredConstructor(
										Part.class);
								defaultUploadedFile = constructor.newInstance(part);
							}

							// If the PrimeFaces FileUpload component is in "simple" mode, then simply set the submitted
							// value of the component to the DefaultUploadedFile instance.
							if (isSimpleMode(attributes)) {

								logger.debug("Setting submittedValue=[{0}]", submittedValue);

								UIInput uiInput = (UIInput) uiComponent;
								uiInput.setSubmittedValue(defaultUploadedFile);
							}

							// Otherwise,
							else {
								logger.debug("Queuing FileUploadEvent for submittedValue=[{0}]", submittedValue);

								// Reflectively create an instance of the PrimeFaces FileUploadEvent class.
								Class<?> uploadedFileClass = Class.forName(FQCN_UPLOADED_FILE);
								Class<?> fileUploadEventClass = Class.forName(FQCN_FILE_UPLOAD_EVENT);
								Constructor<?> constructor = fileUploadEventClass.getConstructor(UIComponent.class,
										uploadedFileClass);
								FacesEvent fileUploadEvent = (FacesEvent) constructor.newInstance(uiComponent,
										defaultUploadedFile);

								// Queue the event.
								uiComponent.queueEvent(fileUploadEvent);
							}
						}
					}
				}
			}
			catch (Exception e) {
				logger.error(e);
			}
		}
	}

	@Override
	public Renderer getWrapped() {
		return wrappedRenderer;
	}
}
