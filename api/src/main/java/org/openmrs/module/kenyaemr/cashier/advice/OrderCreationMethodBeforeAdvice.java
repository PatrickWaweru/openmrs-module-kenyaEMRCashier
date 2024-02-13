package org.openmrs.module.kenyaemr.cashier.advice;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import org.openmrs.*;
import org.openmrs.api.OrderService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemr.cashier.api.IBillService;
import org.openmrs.module.kenyaemr.cashier.api.IBillableItemsService;
import org.openmrs.module.kenyaemr.cashier.api.ICashPointService;
import org.openmrs.module.kenyaemr.cashier.api.ItemPriceService;
import org.openmrs.module.kenyaemr.cashier.api.model.*;
import org.openmrs.module.kenyaemr.cashier.api.search.BillableServiceSearch;
import org.openmrs.module.kenyaemr.cashier.exemptions.BillingExemptions;
import org.openmrs.module.kenyaemr.cashier.util.Utils;
import org.openmrs.module.stockmanagement.api.StockManagementService;
import org.openmrs.module.stockmanagement.api.model.StockItem;
import org.springframework.aop.MethodBeforeAdvice;

public class OrderCreationMethodBeforeAdvice implements MethodBeforeAdvice {

    OrderService orderService = Context.getOrderService();
    IBillService billService = Context.getService(IBillService.class);
    StockManagementService stockService = Context.getService(StockManagementService.class);
    ItemPriceService priceService = Context.getService(ItemPriceService.class);
    ICashPointService cashPointService = Context.getService(ICashPointService.class);

    // todo remove static variables
    @Override
    public void before(Method method, Object[] args, Object target) throws Throwable {
        try {
            // Extract the Order object from the arguments
            ProgramWorkflowService workflowService = Context.getProgramWorkflowService();
            if (method.getName().equals("saveOrder") && args.length > 0 && args[0] instanceof Order) {
                Order order = (Order) args[0];
                if (!fetchPatientPayingCategory(order)) {
                    return;
                }

                // Check if the order already exists by looking at the database
                if (orderService.getOrderByUuid(order.getUuid()) != null) {
                    // This is an existing order being updated
                    System.out.println("Order is being updated: " + order.getOrderId());
                } else {
                    // This is a new order
                    System.out.println("New order is being created");
                    // Add bill item to Bill
                    Patient patient = order.getPatient();
                    String patientUUID = patient.getUuid();
                    String cashierUUID = Context.getAuthenticatedUser().getUuid();
                    String cashpointUUID = Utils.getDefaultLocation().getUuid();
                    System.out.println("Patient: " + patientUUID + " cashier: " + cashierUUID + " cash point: " + cashpointUUID);
                    if(order instanceof DrugOrder) {
                        DrugOrder drugOrder = (DrugOrder) order;
                        Integer drugID = drugOrder.getDrug() != null ? drugOrder.getDrug().getDrugId() : 0;
                        String drugUUID = drugOrder.getDrug() != null ? drugOrder.getDrug().getConcept().getUuid() : "";
                        double drugQuantity = drugOrder.getQuantity() != null ? drugOrder.getQuantity() : 0.0;
                        List<StockItem> stockItems = stockService.getStockItemByDrug(drugID);
                        System.out.println("Drug id: " + drugID + " Drug UUID: " + drugUUID + " Drug Quantity: " + drugQuantity);
                        if (!stockItems.isEmpty()) {
                            // check from the list for all exemptions
                            boolean isExempted = checkIfOrderIsExempted(workflowService, order, BillingExemptions.COMMODITIES);

                            BillStatus lineItemStatus = isExempted ? BillStatus.EXEMPTED : BillStatus.PENDING;

                            addBillItemToBill(order, patient, cashierUUID, cashpointUUID, stockItems.get(0), null, (int) drugQuantity, order.getDateActivated(), lineItemStatus);
                        }
                    } else if(order instanceof TestOrder) {
                        TestOrder testOrder = (TestOrder) order;
                        int testID = testOrder.getId() != null ? testOrder.getId() : 0;
                        String testUUID = testOrder.getUuid() != null ? testOrder.getUuid() : "";
                        BillableService searchTemplate = new BillableService();
                        searchTemplate.setConcept(testOrder.getConcept());
                        searchTemplate.setServiceStatus(BillableServiceStatus.ENABLED);

                        IBillableItemsService service = Context.getService(IBillableItemsService.class);
                        List<BillableService> searchResult = service.findServices(new BillableServiceSearch(searchTemplate));
                        if (!searchResult.isEmpty()) {
                            System.out.println("service was found");
                            System.out.println(searchResult.get(0).getConcept().getUuid());

                            boolean isExempted = checkIfOrderIsExempted(workflowService, order, BillingExemptions.SERVICES);
                            BillStatus lineItemStatus = isExempted ? BillStatus.EXEMPTED : BillStatus.PENDING;
                            addBillItemToBill(order, patient, cashierUUID, cashpointUUID, null, searchResult.get(0), 1, order.getDateActivated(), lineItemStatus);

                        } else {
                            System.out.println("concept was not found");
                        }
                        System.out.println("Test id: " + testID + " Test UUID: " + testUUID);
                    }
                }
            }
        } catch(Exception e) {
            System.err.println("Error intercepting order before creation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks if an order concept is in the exemptions list
     * @param workflowService
     * @param order
     * @param config
     * @return
     */
    private boolean checkIfOrderIsExempted(ProgramWorkflowService workflowService, Order order, Map<String, Set<Integer>> config) {
        if (config == null || order == null) {
            return false;
        }
        if (config.get("all") != null && config.get("all").contains(order.getConcept().getConceptId())) {
            return true;
        }
        // check in programs list
        List<String> programExemptions = config.keySet().stream().filter(key -> key.startsWith("program:")).collect(Collectors.toList());
        if (programExemptions.size() > 0) {
            List<PatientProgram> programs = workflowService.getPatientPrograms(order.getPatient(), null, null, null, new Date(), null, false);
            Set<String> activeEnrollments = new HashSet<>();
            programs.forEach(patientProgram -> {
                if (patientProgram.getActive()) {
                    activeEnrollments.add(patientProgram.getProgram().getName());
                }
            });

            for (String programEntry: programExemptions) {
                if (programEntry.contains(":")) { // this is our convention to distinguish program exemption
                    String programName = programEntry.substring(programEntry.indexOf(":") + 1);
                    //check if patient is active in the program
                    if (activeEnrollments.contains(programName)) {
                        // check if order is exempted
                        if (config.get(programEntry).contains(order.getConcept().getConceptId())) {
                            return true;
                        }

                    }
                }
            }
        }

        // check age category
        if (order.getPatient().getAge() < 5 && config.get("age<5") != null && config.get("age<5").contains(order.getConcept().getConceptId())) {
            return true;
        }
        return false;
    }

    /**
     * Adds a bill item to the cashier module
     * @param patient
     * @param cashierUUID
     * @param cashpointUUID
     */
    public void addBillItemToBill(Order order, Patient patient, String cashierUUID, String cashpointUUID, StockItem stockitem, BillableService service, Integer quantity, Date orderDate, BillStatus lineItemStatus) {
        boolean ret = false;
        try {
            // Search for a bill
            Bill activeBill = new Bill();
            activeBill.setPatient(patient);
            activeBill.setStatus(BillStatus.PENDING);
//            BillSearch billSearch = new BillSearch(searchBill);
//            List<Bill> bills = billService.getBills(billSearch);
//            Bill activeBill = null;
//
//            //search for any pending bill for today
//            for (Bill currentBill : bills) {
//                //Get the bill date
//                if(DateUtils.isSameDay(currentBill.getDateCreated(), orderDate != null ? orderDate : new Date())) {
//                    activeBill = currentBill;
//                    break;
//                }
//            }
//
//            // if there is no active bill for today, we create one
//            if(activeBill == null){
//                activeBill = searchBill;
//            }

            // Bill Item
            BillLineItem billLineItem = new BillLineItem();
            List<CashierItemPrice> itemPrices = new ArrayList<>();
            if (stockitem != null) {
                billLineItem.setItem(stockitem);
                itemPrices = priceService.getItemPrice(stockitem);
            } else if (service != null) {
                billLineItem.setBillableService(service);
                itemPrices = priceService.getServicePrice(service);
            }

            if (!itemPrices.isEmpty()) {
                List<CashierItemPrice> matchingPrices = itemPrices.stream().filter(p -> p.getPaymentMode().getUuid().equals(fetchPatientPayment(order))).collect(Collectors.toList());
                billLineItem.setPrice(matchingPrices.isEmpty() ? itemPrices.get(0).getPrice() : matchingPrices.get(0).getPrice());
            } else {
                billLineItem.setPrice(new BigDecimal(0.0));
            }
            billLineItem.setQuantity(quantity);
            billLineItem.setPaymentStatus(lineItemStatus);
            billLineItem.setLineItemOrder(0);

            // Bill
            User user = Context.getAuthenticatedUser();
            List<Provider> providers = new ArrayList<>(Context.getProviderService().getProvidersByPerson(user.getPerson()));

            if (!providers.isEmpty()) {
                activeBill.setCashier(providers.get(0));
                List<CashPoint> cashPoints = cashPointService.getAll();
                activeBill.setCashPoint(cashPoints.get(0));
                activeBill.addLineItem(billLineItem);
                activeBill.setStatus(BillStatus.PENDING);
                billService.save(activeBill);
            } else {
                System.out.println("User is not a provider");
            }

        } catch (Exception ex) {
            System.err.println("Error sending the bill item: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String fetchPatientPayment(Order order) {
        String patientPayingMethod = "";
        Collection<VisitAttribute> visitAttributeList = order.getEncounter().getVisit().getActiveAttributes();

        for (VisitAttribute attribute : visitAttributeList) {
            if (attribute.getAttributeType().getUuid().equals("c39b684c-250f-4781-a157-d6ad7353bc90") && !attribute.getVoided()) {
                patientPayingMethod = attribute.getValueReference();
            }
        }
        return patientPayingMethod;
    }

    private boolean fetchPatientPayingCategory(Order order) {
        boolean isPaying = false;
        Collection<VisitAttribute> visitAttributeList = order.getEncounter().getVisit().getActiveAttributes();

        for (VisitAttribute attribute : visitAttributeList) {
            if (attribute.getAttributeType().getUuid().equals("caf2124f-00a9-4620-a250-efd8535afd6d") && attribute.getValueReference().equals("1c30ee58-82d4-4ea4-a8c1-4bf2f9dfc8cf")) {
               return true;
            }
        }

        return isPaying;
    }
}

