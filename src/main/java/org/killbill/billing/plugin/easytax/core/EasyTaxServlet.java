/*  Copyright 2017 SolarNetwork Foundation
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.killbill.billing.plugin.easytax.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.killbill.billing.plugin.core.PluginServlet;
import org.killbill.billing.plugin.easytax.api.EasyTaxDao;
import org.killbill.billing.tenant.api.Tenant;
import org.killbill.clock.Clock;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;

public class EasyTaxServlet extends PluginServlet {

    private static final long serialVersionUID = -85672396888778584L;

    /**
     * A boolean request parameter to find only with a valid date set to the system time.
     */
    public static final String VALID_NOW_PARAM = "validNow";

    /**
     * A ISO8601 date/time request parameter holding an account-local validity date.
     */
    public static final String VALID_DATE = "validDate";

    /** The JSON content type with UTF-8 encoding. */
    public static final String APPLICATION_JSON_UTF8 = APPLICATION_JSON + ";charset=UTF-8";

    private static final ObjectMapper JSON_MAPPER = defaultObjectMapper();

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(Include.NON_NULL)
                .registerModule(new JodaModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true)
                .setTimeZone(TimeZone.getTimeZone("UTC"));

        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, BigDecimalStringSerializer.INSTANCE);
        objectMapper.registerModule(module);
        return objectMapper;
    }

    // CHECKSTYLE IGNORE LineLength FOR NEXT 1 LINE
    private static final TypeReference<List<EasyTaxTaxCode>> TAX_CODE_LIST_MAPPING_TYPE = new TypeReference<List<EasyTaxTaxCode>>() {
    };

    /**
     * Regexp for tax code URLs like {@literal /taxCodes/{taxZone}/{productName}/{taxCode}}.
     */
    private static final Pattern TAX_CODES_URL_PATTERN = Pattern
            .compile("/taxCodes(/([^/]+)(/([^/]+))?(/([^/]+))?)?");

    private final EasyTaxDao dao;
    private final Clock clock;

    public EasyTaxServlet(final EasyTaxDao dao, final Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    /**
     * Get one or more tax codes.
     * 
     * <p>
     * The response will always be an array (possibly empty) of {@link EasyTaxTaxCode} objects. The
     * path pattern <code>/taxCodes/{taxZone}/{productName}/{taxCode}</code> defines which codes are
     * returned. The supported path patterns are:
     * </p>
     * 
     * <table>
     * <tbody>
     * <tr>
     * <td><code>/taxCodes</code></td>
     * <td>all tax codes for the active tenant</td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}</code></td>
     * <td>all tax codes for the active tenant and tax zone <code>taxZone</code></td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}/{productName}</code></td>
     * <td>all tax codes for the active tenant, tax zone <code>taxZone</code>, and product
     * <code>productName</code></td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}/{productName}/{taxCode}</code></td>
     * <td>all tax codes for the active tenant, tax zone <code>taxZone</code>, product
     * <code>productName</code>, and code <code>taxCode</code></td>
     * </tr>
     * </tbody>
     * </table>
     * 
     * <p>
     * In addition, the following parameters may be provided to filter the results further:
     * </p>
     * 
     * <ul>
     * <li><code>validDate</code> - a date in ISO8601 format to restrict the returned codes to based
     * on their valid date range</li>
     * <li><code>validNow</code> - if {@literal true} then restrict the returned codes to those
     * whose valid date range contains the current system time</li>
     * </ul>
     * 
     * @param req
     *            the request
     * @param resp
     *            the response
     * @throws ServletException
     *             if a servlet error occurs
     * @throws IOException
     *             if an IO error occurs
     */
    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {
        final Tenant tenant = getTenant(req);
        if (tenant == null) {
            buildNotFoundResponse("No tenant specified", resp);
            return;
        }

        final String pathInfo = req.getPathInfo();
        final Matcher matcher = TAX_CODES_URL_PATTERN.matcher(pathInfo);
        if (matcher.matches()) {
            String taxZone = matcher.group(2);
            String productName = matcher.group(4);
            String taxCode = matcher.group(6);
            DateTime date = null;

            if ("true".equalsIgnoreCase(req.getParameter(VALID_NOW_PARAM))) {
                date = clock.getUTCNow();
            } else if (req.getParameter(VALID_DATE) != null) {
                // parse date (including time zone) then force to UTC (for unit tests mostly)
                date = ISODateTimeFormat.dateOptionalTimeParser()
                        .parseDateTime(req.getParameter(VALID_DATE)).withZone(DateTimeZone.UTC);
            }

            respondTaxCodes(tenant, taxZone, productName, taxCode, date, resp);
        } else {
            buildNotFoundResponse("Resource " + pathInfo + " not found", resp);
        }
    }

    /**
     * Save a single tax code or multiple tax codes.
     * 
     * <p>
     * If the path matches a specific tax code (in the pattern
     * <code>/taxCodes/{taxZone}/{productName}/{taxCode}</code> then the request body is expected to
     * contain a single {@link EasyTaxTaxCode} object that contains:
     * </p>
     * 
     * <ol>
     * <li><b>tax_rate</b> - the tax rate</li>
     * <li><b>valid_from_date</b> - the valid from time stamp, in ISO8601 format</li>
     * <li><b>valid_to_date</b> (optional) - the valid to time stamp, in ISO8601 format</li>
     * </ol>
     * 
     * <p>
     * If the path does not match a specific tax code then the request body is expected to contain
     * an array of {@link EasyTaxCode} objects that contain all details other than
     * <b>created_date</b> and <b>tenant_id</b>, which will be populated automatically.
     * </p>
     * 
     * @param req
     *            the request
     * @param resp
     *            the response
     * @throws ServletException
     *             if a servlet error occurs
     * @throws IOException
     *             if an IO error occurs
     */
    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {
        final Tenant tenant = getTenant(req);
        if (tenant == null) {
            buildNotFoundResponse("No tenant specified", resp);
            return;
        }

        final String pathInfo = req.getPathInfo();
        final Matcher matcher = TAX_CODES_URL_PATTERN.matcher(pathInfo);
        if (matcher.matches()) {
            String taxZone = matcher.group(2);
            String productName = matcher.group(4);
            String taxCode = matcher.group(6);
            if (taxCode != null && productName != null && taxZone != null) {
                addTaxCode(tenant, taxZone, productName, taxCode, req, resp);
            } else {
                addTaxCodes(tenant, req, resp);
            }
        } else {
            buildNotFoundResponse("Resource " + pathInfo + " not found", resp);
        }
    }

    /**
     * Delete one or more tax codes.
     * 
     * <p>
     * The path pattern <code>/taxCodes/{taxZone}/{productName}/{taxCode}</code> defines which codes
     * are deleted. The supported path patterns are:
     * </p>
     * 
     * <table>
     * <tbody>
     * <tr>
     * <td><code>/taxCodes</code></td>
     * <td>all tax codes for the active tenant</td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}</code></td>
     * <td>all tax codes for the active tenant and tax zone <code>taxZone</code></td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}/{productName}</code></td>
     * <td>all tax codes for the active tenant, tax zone <code>taxZone</code>, and product
     * <code>productName</code></td>
     * </tr>
     * <tr>
     * <td><code>/taxCodes/{taxZone}/{productName}/{taxCode}</code></td>
     * <td>all tax codes for the active tenant, tax zone <code>taxZone</code>, product
     * <code>productName</code>, and code <code>taxCode</code></td>
     * </tr>
     * </tbody>
     * </table>
     * 
     * @param req
     *            the request
     * @param resp
     *            the response
     * @throws ServletException
     *             if a servlet error occurs
     * @throws IOException
     *             if an IO error occurs
     */
    @Override
    protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {
        final Tenant tenant = getTenant(req);
        if (tenant == null) {
            buildNotFoundResponse("No tenant specified", resp);
            return;
        }

        final String pathInfo = req.getPathInfo();
        final Matcher matcher = TAX_CODES_URL_PATTERN.matcher(pathInfo);
        if (matcher.matches()) {
            String taxZone = matcher.group(2);
            String productName = matcher.group(4);
            String taxCode = matcher.group(6);
            deleteTaxCodes(tenant, taxZone, productName, taxCode, resp);
        } else {
            buildNotFoundResponse("Resource " + pathInfo + " not found", resp);
        }
    }

    private void respondTaxCodes(final Tenant tenant, final String taxZone,
            final String productName, final String taxCode, final DateTime validDate,
            final HttpServletResponse resp) throws IOException {
        final List<EasyTaxTaxCode> taxCodesRecords;
        try {
            taxCodesRecords = dao.getTaxCodes(tenant.getId(), taxZone, productName, taxCode,
                    validDate);
        } catch (final SQLException e) {
            buildErrorResponse(e, resp);
            return;
        }

        final byte[] data = JSON_MAPPER.writeValueAsBytes(taxCodesRecords);
        resp.setContentType(APPLICATION_JSON_UTF8);
        buildOKResponse(data, resp);
    }

    private void addTaxCode(final Tenant tenant, final String taxZone, final String productName,
            final String taxCode, final ServletRequest req, final HttpServletResponse resp)
            throws IOException {
        final EasyTaxTaxCode code = JSON_MAPPER.readValue(getRequestData(req),
                EasyTaxTaxCode.class);
        code.setCreatedDate(clock.getUTCNow());
        code.setKbTenantId(tenant.getId());
        code.setTaxZone(taxZone);
        code.setProductName(productName);
        code.setTaxCode(taxCode);
        try {
            dao.saveTaxCode(code);
        } catch (final SQLException e) {
            buildErrorResponse(e, resp);
            return;
        }

        buildCreatedResponse(
                "/plugins/killbill-easytax/taxCodes/" + taxZone + "/" + productName + "/" + taxCode,
                resp);
    }

    private void addTaxCodes(final Tenant tenant, final ServletRequest req,
            final HttpServletResponse resp) throws IOException {
        final List<EasyTaxTaxCode> taxCodes = JSON_MAPPER.readValue(getRequestData(req),
                TAX_CODE_LIST_MAPPING_TYPE);
        if (taxCodes == null) {
            return;
        }
        DateTime now = clock.getUTCNow();
        UUID tenantId = tenant.getId();
        for (EasyTaxTaxCode code : taxCodes) {
            code.setKbTenantId(tenantId);
            code.setCreatedDate(now);
        }
        try {
            dao.saveTaxCodes(taxCodes);
        } catch (final SQLException e) {
            buildErrorResponse(e, resp);
            return;
        }

        buildOKResponse(null, resp);
    }

    private void deleteTaxCodes(final Tenant tenant, final String taxZone, final String productName,
            final String taxCode, final HttpServletResponse resp) throws IOException {
        try {
            dao.removeTaxCodes(tenant.getId(), taxZone, productName, taxCode);
        } catch (final SQLException e) {
            buildErrorResponse(e, resp);
            return;
        }

        buildOKResponse(null, resp);
    }

}