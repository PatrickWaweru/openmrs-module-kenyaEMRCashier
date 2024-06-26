/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.kenyaemr.cashier.web.controller;

import java.util.Set;

import org.openmrs.Privilege;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemr.cashier.base.controller.RoleCreationControllerBase;
import org.openmrs.module.kenyaemr.cashier.api.util.PrivilegeConstants;
import org.openmrs.module.kenyaemr.cashier.web.CashierWebConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller to manage the Cashier Role Creation page.
 */
@Controller
@RequestMapping(CashierWebConstants.CASHIER_ROLE_ROOT)
public class CashierRoleController extends RoleCreationControllerBase {

	public CashierRoleController() {

	}

	@Override
	public UserService getUserService() {
		return Context.getUserService();
	}

	@Override
	public Set<Privilege> privileges() {
		return PrivilegeConstants.getDefaultPrivileges();
	}
}
