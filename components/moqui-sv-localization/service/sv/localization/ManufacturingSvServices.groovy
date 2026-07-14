import org.moqui.context.ExecutionContext

import javax.xml.transform.stream.StreamSource
import java.math.RoundingMode
import java.sql.Timestamp

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext ec() { (ExecutionContext) ctx('ec') }

BigDecimal bd(Object value, BigDecimal defaultValue = BigDecimal.ZERO) {
    if (value == null) return defaultValue
    if (value instanceof BigDecimal) return value
    return new BigDecimal(value.toString())
}

boolean boolValue(Object value, boolean defaultValue = true) {
    if (value == null || value == '') return defaultValue
    if (value instanceof Boolean) return value
    String text = value.toString().trim()
    return text.equalsIgnoreCase('true') || text.equalsIgnoreCase('y')
}

def findWorkEffort(ExecutionContext ec, String workEffortId) {
    ec.entity.find('mantle.work.effort.WorkEffort')
            .condition('workEffortId', workEffortId).one()
}

def findProduct(ExecutionContext ec, String productId) {
    ec.entity.find('mantle.product.Product')
            .condition('productId', productId).one()
}

Map createSvProductionRun() {
    return createRun([
            orderId                : ctx('orderId'),
            orderPartSeqId         : ctx('orderPartSeqId'),
            productId              : ctx('productId'),
            quantity               : ctx('quantity'),
            facilityId             : ctx('facilityId'),
            ownerPartyId           : ctx('ownerPartyId'),
            customerPartyId        : ctx('customerPartyId'),
            sourceWorkEffortId     : ctx('sourceWorkEffortId'),
            linkOrderItems         : ctx('linkOrderItems'),
            workEffortName         : ctx('workEffortName'),
            estimatedStartDate     : ctx('estimatedStartDate'),
            estimatedCompletionDate: ctx('estimatedCompletionDate'),
            estimatedWorkDuration  : ctx('estimatedWorkDuration'),
            actualCost             : ctx('actualCost'),
            priority               : ctx('priority'),
            description            : ctx('description')
    ])
}

Map createSvProductionRunFromOrder() {
    return createRun([
            orderId                : ctx('orderId'),
            orderPartSeqId         : ctx('orderPartSeqId'),
            productId              : ctx('productId'),
            quantity               : ctx('quantity'),
            facilityId             : ctx('facilityId'),
            ownerPartyId           : ctx('ownerPartyId'),
            customerPartyId        : ctx('customerPartyId'),
            sourceWorkEffortId     : ctx('sourceWorkEffortId'),
            linkOrderItems         : ctx('linkOrderItems'),
            workEffortName         : ctx('workEffortName'),
            estimatedStartDate     : ctx('estimatedStartDate'),
            estimatedCompletionDate: ctx('estimatedCompletionDate'),
            estimatedWorkDuration  : ctx('estimatedWorkDuration'),
            actualCost             : ctx('actualCost'),
            priority               : ctx('priority'),
            description            : ctx('description')
    ])
}

Map createRun(Map input) {
    ExecutionContext ec = ec()
    def orderPart = null
    if (input.orderId && input.orderPartSeqId) {
        orderPart = ec.entity.find('mantle.order.OrderPart')
                .condition([orderId: input.orderId, orderPartSeqId: input.orderPartSeqId]).one()
        if (!orderPart) {
            ec.message.addError("Orden ${input.orderId} parte ${input.orderPartSeqId} no encontrada")
            return [:]
        }
    }

    String productId = input.productId
    def product = findProduct(ec, productId)
    if (!product) {
        ec.message.addError("Producto ${productId} no encontrado")
        return [:]
    }

    def sourceWorkEffort = null
    if (input.sourceWorkEffortId) {
        sourceWorkEffort = findWorkEffort(ec, (String) input.sourceWorkEffortId)
        if (!sourceWorkEffort) {
            ec.message.addError("Plantilla/corrida origen ${input.sourceWorkEffortId} no encontrada")
            return [:]
        }
    }

    String facilityId = input.facilityId ?: orderPart?.facilityId
    if (!facilityId) {
        ec.message.addError('Debe indicar bodega o linea de produccion')
        return [:]
    }

    Timestamp startDate = (Timestamp) (input.estimatedStartDate ?: ec.user.nowTimestamp)
    BigDecimal quantity = bd(input.quantity, BigDecimal.ONE)
    String runName = input.workEffortName ?: (orderPart ? "${product.productName} - Orden ${input.orderId}" : product.productName)

    Map runParams = [
            workEffortName          : runName,
            description             : input.description,
            facilityId              : facilityId,
            ownerPartyId            : input.ownerPartyId ?: orderPart?.vendorPartyId,
            estimatedStartDate      : startDate,
            estimatedCompletionDate : input.estimatedCompletionDate,
            estimatedWorkDuration   : input.estimatedWorkDuration,
            actualCost              : input.actualCost,
            priority                : input.priority,
            produceProductId        : productId,
            produceEstimatedQuantity: quantity
    ].findAll { it.value != null }

    Map runResult = ec.service.sync()
            .name('mantle.work.ManufacturingServices.create#ProductionRun')
            .parameters(runParams).call()
    String workEffortId = runResult.workEffortId

    String customerPartyId = input.customerPartyId ?: orderPart?.customerPartyId
    if (customerPartyId) {
        ec.entity.find('mantle.work.effort.WorkEffortParty')
                .condition([workEffortId: workEffortId, partyId: customerPartyId, roleTypeId: 'Customer'])
                .one() ?: ec.service.sync().name('create#mantle.work.effort.WorkEffortParty')
                .parameters([workEffortId: workEffortId, partyId: customerPartyId,
                             roleTypeId : 'Customer', fromDate: startDate]).call()
    }

    Integer copiedBomCount = sourceWorkEffort ?
            copyBomFromSourceRun(ec, (String) sourceWorkEffort.workEffortId, workEffortId, productId, quantity, startDate) : 0
    if (ec.message.hasError()) return [workEffortId: workEffortId, copiedBomCount: copiedBomCount, linkedOrderItemCount: 0]

    Integer linkedOrderItemCount = 0
    if (input.orderId && boolValue(input.linkOrderItems, true)) {
        linkedOrderItemCount = linkOrderItemsToRun(ec, (String) input.orderId, (String) input.orderPartSeqId,
                productId, workEffortId)
    }

    return [workEffortId: workEffortId, copiedBomCount: copiedBomCount, linkedOrderItemCount: linkedOrderItemCount]
}

Integer copyBomFromSourceRun(ExecutionContext ec, String sourceWorkEffortId, String targetWorkEffortId,
                             String targetProductId, BigDecimal targetQuantity, Timestamp fromDate) {
    List sourceProduce = ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: sourceWorkEffortId, typeEnumId: 'WeptProduce'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()
    def sourceProduceLine = sourceProduce.find { it.productId == targetProductId } ?: (sourceProduce ? sourceProduce[0] : null)
    BigDecimal sourceQuantity = bd(sourceProduceLine?.estimatedQuantity, BigDecimal.ONE)
    if (sourceQuantity.compareTo(BigDecimal.ZERO) <= 0) sourceQuantity = BigDecimal.ONE
    BigDecimal factor = targetQuantity.divide(sourceQuantity, 8, RoundingMode.HALF_UP)

    Set<String> targetConsumeProductIds = ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: targetWorkEffortId, typeEnumId: 'WeptConsume'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()
            .collect { it.productId as String } as Set<String>

    Map<String, Map> aggregate = [:]
    ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: sourceWorkEffortId, typeEnumId: 'WeptConsume'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp)
            .orderBy('productId,fromDate').list().each { sourceLine ->
        String productId = sourceLine.productId
        if (targetConsumeProductIds.contains(productId)) return
        BigDecimal scaledQuantity = bd(sourceLine.estimatedQuantity, BigDecimal.ZERO)
                .multiply(factor).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        if (scaledQuantity.compareTo(BigDecimal.ZERO) <= 0) return

        Map line = aggregate[productId] ?: [quantity: BigDecimal.ZERO, estimatedCost: null]
        line.quantity = ((BigDecimal) line.quantity).add(scaledQuantity)
        if (sourceLine.estimatedCost != null) line.estimatedCost = bd(sourceLine.estimatedCost)
        aggregate[productId] = line
    }

    Integer copiedCount = 0
    aggregate.each { String productId, Map line ->
        Map params = [
                workEffortId     : targetWorkEffortId,
                productId        : productId,
                typeEnumId       : 'WeptConsume',
                statusId         : 'WepdCreated',
                fromDate         : fromDate,
                estimatedQuantity: line.quantity
        ]
        if (line.estimatedCost != null) params.estimatedCost = line.estimatedCost
        ec.service.sync().name('create#mantle.work.effort.WorkEffortProduct').parameters(params).call()
        if (ec.message.hasError()) return copiedCount
        copiedCount++
    }
    return copiedCount
}

Integer linkOrderItemsToRun(ExecutionContext ec, String orderId, String orderPartSeqId,
                            String productId, String workEffortId) {
    def find = ec.entity.find('mantle.order.OrderItem')
            .condition([orderId: orderId, productId: productId])
    if (orderPartSeqId) find.condition('orderPartSeqId', orderPartSeqId)
    List orderItems = find.list()

    Integer linkedCount = 0
    orderItems.each { orderItem ->
        def existing = ec.entity.find('mantle.order.OrderItemWorkEffort').condition([
                orderId: orderId, orderItemSeqId: orderItem.orderItemSeqId, workEffortId: workEffortId
        ]).one()
        if (existing) return
        ec.service.sync().name('create#mantle.order.OrderItemWorkEffort').parameters([
                orderId        : orderId,
                orderItemSeqId : orderItem.orderItemSeqId,
                workEffortId   : workEffortId,
                requiredWork   : 'Y',
                forStatusId    : 'OrderCompleted'
        ]).call()
        if (!ec.message.hasError()) linkedCount++
    }
    return linkedCount
}

Map calculateRunCost() {
    ExecutionContext ec = ec()
    String workEffortId = (String) ctx('workEffortId')
    def workEffort = findWorkEffort(ec, workEffortId)
    if (!workEffort) {
        ec.message.addError("Orden ${workEffortId} no encontrada")
        return [:]
    }

    List consumeProducts = ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: workEffortId, typeEnumId: 'WeptConsume'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()

    BigDecimal materialCostEstimated = BigDecimal.ZERO
    List materialLines = []
    Map<String, Map> realCostByProduct = [:]

    ec.entity.find('mantle.product.issuance.AssetIssuance')
            .condition('workEffortId', workEffortId).list().each { iss ->
        def asset = ec.entity.find('mantle.product.asset.Asset')
                .condition('assetId', iss.assetId).one()
        String productId = asset?.productId ?: iss.productId
        if (!productId) return
        BigDecimal issuedQty = bd(iss.quantity, BigDecimal.ZERO)
        BigDecimal unitCost = bd(asset?.acquireCost, BigDecimal.ZERO)
        Map productCost = realCostByProduct[productId] ?: [quantity: BigDecimal.ZERO, cost: BigDecimal.ZERO]
        productCost.quantity = ((BigDecimal) productCost.quantity) + issuedQty
        productCost.cost = ((BigDecimal) productCost.cost) + (unitCost * issuedQty)
        realCostByProduct[productId] = productCost
    }

    consumeProducts.each { wep ->
        def product = findProduct(ec, wep.productId)
        BigDecimal qty = bd(wep.estimatedQuantity, BigDecimal.ZERO)
        Map avgCost = ec.service.sync().name('sv.localization.InventorySvServices.calculate#AverageCost')
                .parameters([productId: wep.productId, ownerPartyId: workEffort.ownerPartyId]).call()
        BigDecimal unitCost = bd(avgCost.averageCost, BigDecimal.ZERO)
        if (unitCost.compareTo(BigDecimal.ZERO) <= 0) unitCost = bd(wep.estimatedCost, BigDecimal.ZERO)
        BigDecimal lineCost = unitCost * qty
        materialCostEstimated += lineCost
        Map realCost = realCostByProduct[wep.productId] ?: [quantity: BigDecimal.ZERO, cost: BigDecimal.ZERO]

        materialLines.add([
                productId         : wep.productId,
                pseudoId          : product?.pseudoId,
                productName       : product?.productName,
                estimatedQuantity : qty,
                unitCost          : unitCost,
                lineCost          : lineCost,
                issuedQuantity    : realCost.quantity,
                realCost          : realCost.cost
        ])
    }

    BigDecimal materialCostReal = realCostByProduct.values()
            .inject(BigDecimal.ZERO) { BigDecimal total, Map productCost -> total + bd(productCost.cost) }

    BigDecimal laborCost = bd(workEffort.actualCost, BigDecimal.ZERO)
    BigDecimal matCost = materialCostReal > BigDecimal.ZERO ? materialCostReal : materialCostEstimated

    return [
            materialCostEstimated: materialCostEstimated,
            materialCostReal     : materialCostReal,
            laborCost            : laborCost,
            totalCost            : matCost + laborCost,
            materialLines        : materialLines
    ]
}

Map issueMaterialsFromBom() {
    ExecutionContext ec = ec()
    String workEffortId = (String) ctx('workEffortId')
    String facilityId = (String) ctx('facilityId')
    boolean allowPartial = boolValue(ctx('allowPartial'), false)
    def workEffort = findWorkEffort(ec, workEffortId)
    if (!workEffort) {
        ec.message.addError("Orden ${workEffortId} no encontrada")
        return [:]
    }

    List consumeProducts = ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: workEffortId, typeEnumId: 'WeptConsume'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()

    Integer issuedCount = 0
    BigDecimal totalIssued = BigDecimal.ZERO
    List issues = []
    List missingMaterials = []
    List plannedIssues = []
    Map<String, BigDecimal> requiredByProduct = [:]

    consumeProducts.each { wep ->
        BigDecimal qty = bd(wep.estimatedQuantity, BigDecimal.ZERO)
        if (qty <= BigDecimal.ZERO) return
        requiredByProduct[wep.productId as String] = (requiredByProduct[wep.productId as String] ?: BigDecimal.ZERO) + qty
    }

    requiredByProduct.each { String productId, BigDecimal requiredQty ->
        BigDecimal alreadyIssued = issuedQuantityForProduct(ec, workEffortId, productId)
        BigDecimal pendingQty = requiredQty - alreadyIssued
        if (pendingQty.compareTo(BigDecimal.ZERO) <= 0) return

        def assetFind = ec.entity.find('mantle.product.asset.Asset')
                .condition([productId: productId, statusId: 'AstAvailable'])
                .condition('availableToPromiseTotal', 'greater', BigDecimal.ZERO)
        if (facilityId) assetFind.condition('facilityId', facilityId)

        BigDecimal remainingQty = pendingQty
        BigDecimal availableQty = BigDecimal.ZERO
        assetFind.orderBy('receivedDate,assetId').list().each { asset ->
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) return
            BigDecimal assetAvailable = bd(asset.availableToPromiseTotal, BigDecimal.ZERO)
            if (assetAvailable.compareTo(BigDecimal.ZERO) <= 0) return
            BigDecimal issueQty = assetAvailable < remainingQty ? assetAvailable : remainingQty
            availableQty += assetAvailable
            plannedIssues.add([
                    productId : productId,
                    assetId   : asset.assetId,
                    facilityId: asset.facilityId,
                    quantity  : issueQty
            ])
            remainingQty -= issueQty
            if (!allowPartial && remainingQty.compareTo(BigDecimal.ZERO) <= 0) return
        }

        if (remainingQty.compareTo(BigDecimal.ZERO) > 0) {
            String facilityText = facilityId ?: 'bodegas disponibles'
            ec.message.addPublic("No hay suficiente inventario de ${productId} en ${facilityText}", 'warning')
            missingMaterials.add([
                    productId        : productId,
                    quantity         : pendingQty,
                    availableQuantity: availableQty,
                    missingQuantity  : remainingQty,
                    facilityId       : facilityId
            ])
        }
    }

    if (missingMaterials && !allowPartial) {
        ec.message.addError('No se despacho inventario; faltan materiales para cubrir el BOM completo')
        return [issuedCount: issuedCount, totalIssued: totalIssued, issues: issues, missingMaterials: missingMaterials]
    }

    for (Map plannedIssue in plannedIssues) {
        ec.service.sync().name('mantle.product.AssetServices.issue#AssetToWorkEffort').parameters([
                assetId          : plannedIssue.assetId,
                workEffortId     : workEffortId,
                quantity         : plannedIssue.quantity,
                noOriginFacility : 'add'
        ]).call()
        if (ec.message.hasError()) {
            return [issuedCount: issuedCount, totalIssued: totalIssued, issues: issues, missingMaterials: missingMaterials]
        }

        def issuance = ec.entity.find('mantle.product.issuance.AssetIssuance')
                .condition([workEffortId: workEffortId, assetId: plannedIssue.assetId])
                .orderBy('-issuedDate').one()
        issuedCount++
        totalIssued += bd(plannedIssue.quantity)
        issues.add([
                productId      : plannedIssue.productId,
                facilityId     : plannedIssue.facilityId,
                assetId        : plannedIssue.assetId,
                quantity       : plannedIssue.quantity,
                assetIssuanceId: issuance?.assetIssuanceId
        ])
    }

    return [issuedCount: issuedCount, totalIssued: totalIssued, issues: issues, missingMaterials: missingMaterials]
}

Map receiveProductionOutput() {
    ExecutionContext ec = ec()
    String workEffortId = (String) ctx('workEffortId')
    String facilityId = (String) ctx('facilityId')
    def workEffort = findWorkEffort(ec, workEffortId)
    if (!workEffort) {
        ec.message.addError("Orden ${workEffortId} no encontrada")
        return [:]
    }

    List produceProducts = ec.entity.find('mantle.work.effort.WorkEffortProduct')
            .condition([workEffortId: workEffortId, typeEnumId: 'WeptProduce'])
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).list()
    if (!produceProducts) {
        ec.message.addError('No hay producto a fabricar en la orden')
        return [:]
    }

    def produceLine = produceProducts[0]
    String productId = produceLine.productId
    BigDecimal quantityAccepted = bd(produceLine.estimatedQuantity, BigDecimal.ONE)
    String receiptFacilityId = facilityId ?: workEffort.facilityId
    Map cost = ec.service.sync().name('sv.localization.ManufacturingSvServices.calculate#RunCost')
            .parameters([workEffortId: workEffortId]).call()
    if (ec.message.hasError()) return [:]
    BigDecimal unitCost = quantityAccepted.compareTo(BigDecimal.ZERO) > 0 ?
            bd(cost.totalCost, BigDecimal.ZERO).divide(quantityAccepted, 6, RoundingMode.HALF_UP).stripTrailingZeros() : BigDecimal.ZERO

    Map recResult = ec.service.sync().name('sv.localization.InventorySvServices.receive#MaterialInventory').parameters([
            productId    : productId,
            facilityId   : receiptFacilityId,
            quantity     : quantityAccepted,
            unitCost     : unitCost,
            workEffortId : workEffortId,
            ownerPartyId : workEffort.ownerPartyId
    ]).call()

    return [
            assetReceiptId  : recResult.assetReceiptId,
            assetId         : recResult.assetId,
            productId       : productId,
            quantityAccepted: quantityAccepted,
            unitCost        : unitCost
    ]
}

Map completeProductionRun() {
    ExecutionContext ec = ec()
    String workEffortId = (String) ctx('workEffortId')
    def workEffort = findWorkEffort(ec, workEffortId)
    if (!workEffort) {
        ec.message.addError("Orden ${workEffortId} no encontrada")
        return [:]
    }

    Map result = [issuedCount: 0, totalIssued: BigDecimal.ZERO, issues: [], missingMaterials: []]

    if (ctx('issueMaterials') != false) {
        Map issueResult = ec.service.sync()
                .name('sv.localization.ManufacturingSvServices.issue#MaterialsFromBom')
                .parameters([workEffortId: workEffortId, facilityId: ctx('issueFacilityId'), allowPartial: false]).call()
        result.issuedCount = issueResult.issuedCount ?: 0
        result.totalIssued = issueResult.totalIssued ?: BigDecimal.ZERO
        result.issues = issueResult.issues ?: []
        result.missingMaterials = issueResult.missingMaterials ?: []
        if (result.missingMaterials && !ec.message.hasError()) {
            ec.message.addError("No se puede cerrar ${workEffortId}; faltan materiales: " +
                    result.missingMaterials.collect { it.productId }.join(', '))
            return result
        }
        if (ec.message.hasError()) return result
    }

    if (ctx('receiveOutput') != false) {
        Map receiveResult = ec.service.sync()
                .name('sv.localization.ManufacturingSvServices.receive#ProductionOutput')
                .parameters([workEffortId: workEffortId, facilityId: ctx('receiveFacilityId'),
                             receivedByUserId: ctx('receivedByUserId')]).call()
        result.putAll(receiveResult)
        if (ec.message.hasError()) return result
    }

    ec.service.sync().name('update#mantle.work.effort.WorkEffort')
            .parameters([workEffortId: workEffortId, statusId: 'WeComplete',
                         actualCompletionDate: ec.user.nowTimestamp]).call()
    result.statusId = 'WeComplete'
    return result
}

BigDecimal issuedQuantityForProduct(ExecutionContext ec, String workEffortId, String productId) {
    BigDecimal issuedQuantity = BigDecimal.ZERO
    ec.entity.find('mantle.product.issuance.AssetIssuance')
            .condition('workEffortId', workEffortId).list().each { issuance ->
        def asset = ec.entity.find('mantle.product.asset.Asset')
                .condition('assetId', issuance.assetId).one()
        String issuedProductId = asset?.productId ?: issuance.productId
        if (issuedProductId == productId) issuedQuantity += bd(issuance.quantity, BigDecimal.ZERO)
    }
    return issuedQuantity
}

Map productionRunContext(ExecutionContext ec, String workEffortId) {
    def workEffort = findWorkEffort(ec, workEffortId)
    if (!workEffort) return null

    Map cost = ec.service.sync().name('sv.localization.ManufacturingSvServices.calculate#RunCost')
            .parameters([workEffortId: workEffortId]).call()
    if (ec.message.hasError()) return null
    List produceRows = ec.entity.find('mantle.work.effort.WorkEffortProductDetail')
            .condition('workEffortId', workEffortId).condition('typeEnumId', 'WeptProduce')
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).orderBy('pseudoId').list()
            .collect { row ->
                [
                        productId         : row.getNoCheckSimple('productId'),
                        pseudoId          : row.getNoCheckSimple('pseudoId'),
                        productName       : row.getNoCheckSimple('productName'),
                        estimatedQuantity : row.getNoCheckSimple('estimatedQuantity'),
                        actualQuantity    : row.getNoCheckSimple('actualQuantity'),
                        receivedQuantity  : row.getNoCheckSimple('receivedQuantity')
                ]
            }
    List consumeRows = ec.entity.find('mantle.work.effort.WorkEffortProductDetail')
            .condition('workEffortId', workEffortId).condition('typeEnumId', 'WeptConsume')
            .conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp).orderBy('pseudoId').list()
            .collect { row ->
                [
                        productId         : row.getNoCheckSimple('productId'),
                        pseudoId          : row.getNoCheckSimple('pseudoId'),
                        productName       : row.getNoCheckSimple('productName'),
                        estimatedQuantity : row.getNoCheckSimple('estimatedQuantity'),
                        facilityId        : workEffort.getNoCheckSimple('facilityId') ?: ''
                ]
            }
    List receiptRows = ec.entity.find('mantle.product.receipt.AssetReceipt')
            .condition('workEffortId', workEffortId).orderBy('receivedDate').list()
            .collect { receipt ->
                def asset = receipt.getNoCheckSimple('assetId') ?
                        ec.entity.find('mantle.product.asset.Asset').condition('assetId', receipt.getNoCheckSimple('assetId')).one() : null
                BigDecimal quantity = bd(receipt.getNoCheckSimple('quantityAccepted') ?:
                        receipt.getNoCheckSimple('quantityIncluded'), BigDecimal.ZERO)
                BigDecimal unitCost = bd(receipt.getNoCheckSimple('unitCost') ?:
                        asset?.getNoCheckSimple('acquireCost'), BigDecimal.ZERO)
                [
                        receivedDate    : receipt.getNoCheckSimple('receivedDate'),
                        facilityId      : asset?.getNoCheckSimple('facilityId') ?: workEffort.getNoCheckSimple('facilityId') ?: '',
                        quantityAccepted: quantity,
                        quantityIncluded: quantity,
                        unitCost        : unitCost,
                        totalCost       : unitCost * quantity
                ]
            }

    return [
            workEffort : workEffort,
            produceList: produceRows,
            consumeList: consumeRows,
            issuanceList: ec.entity.find('mantle.product.issuance.AssetIssuance')
                    .condition('workEffortId', workEffortId).orderBy('issuedDate').list(),
            receiptList : receiptRows,
            partyList   : ec.entity.find('mantle.work.effort.WorkEffortAndPartyDetail')
                    .condition('workEffortId', workEffortId).conditionDate('fromDate', 'thruDate', ec.user.nowTimestamp)
                    .orderBy('firstName,lastName,organizationName').list(),
            cost        : [
                    materials: cost.materialCostReal > BigDecimal.ZERO ? cost.materialCostReal : cost.materialCostEstimated,
                    labor    : cost.laborCost ?: BigDecimal.ZERO,
                    overhead : BigDecimal.ZERO,
                    total    : cost.totalCost ?: BigDecimal.ZERO
            ] + cost
    ]
}

Map renderOrdenProduccionPdf() {
    return renderPdf((String) ctx('workEffortId'), 'OrdenProduccion.xsl-fo.ftl', 'orden-produccion')
}

Map renderHojaCierre() {
    return renderPdf((String) ctx('workEffortId'), 'HojaCosteo.xsl-fo.ftl', 'hoja-cierre')
}

Map renderHojaRequisicionPdf() {
    return renderPdf((String) ctx('workEffortId'), 'HojaRequisicion.xsl-fo.ftl', 'hoja-requisicion')
}

Map renderProductoTerminadoPdf() {
    return renderPdf((String) ctx('workEffortId'), 'ProductoTerminado.xsl-fo.ftl', 'producto-terminado')
}

Map renderPdf(String workEffortId, String templateName, String filenamePrefix) {
    ExecutionContext ec = ec()
    Map data = productionRunContext(ec, workEffortId)
    if (data == null) {
        ec.message.addError("Orden ${workEffortId} no encontrada")
        return [rendered: false, message: 'Orden de produccion no encontrada']
    }

    String templateLocation = "component://moqui-sv-localization/template/manufacturing/${templateName}"
    Map renderContext = ec.context
    Map values = [
            svWorkEffort  : data.workEffort,
            svProduceList : data.produceList,
            svConsumeList : data.consumeList,
            svIssuanceList: data.issuanceList,
            svReceiptList : data.receiptList,
            svPartyList   : data.partyList,
            svCost        : data.cost,
            generatedAt   : ec.user.nowTimestamp
    ]
    Map oldValues = values.collectEntries { String key, Object value -> [(key): renderContext.get(key)] }
    Map hadValues = values.collectEntries { String key, Object value -> [(key): renderContext.containsKey(key)] }

    try {
        values.each { String key, Object value -> renderContext.put(key, value) }
        String xslFo = ec.resource.template(templateLocation, 'ftl')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        Integer pageCount
        synchronized (org.moqui.fop.FopToolFactory.class) {
            pageCount = ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFo)), null, baos, 'application/pdf')
        }
        return [
                rendered        : true,
                templateLocation: templateLocation,
                xslFoText       : xslFo,
                pdfBytes        : baos.toByteArray(),
                pdfSize         : baos.size(),
                pageCount       : pageCount,
                filename        : "${filenamePrefix}-${workEffortId}.pdf",
                contentType     : 'application/pdf'
        ]
    } finally {
        oldValues.each { String key, Object value ->
            if (hadValues[key]) renderContext.put(key, value) else renderContext.remove(key)
        }
    }
}
