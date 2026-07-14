import org.moqui.context.ExecutionContext

import java.math.RoundingMode
import java.sql.Timestamp

Object ctx(String name) {
    Binding scriptBinding = getBinding()
    return scriptBinding?.hasVariable(name) ? scriptBinding.getVariable(name) : null
}

ExecutionContext ec() { (ExecutionContext) ctx('ec') }

BigDecimal bd(Object value, BigDecimal defaultValue = BigDecimal.ZERO) {
    if (value == null || value == '') return defaultValue
    if (value instanceof BigDecimal) return value
    return new BigDecimal(value.toString())
}

boolean boolValue(Object value, boolean defaultValue = true) {
    if (value == null || value == '') return defaultValue
    if (value instanceof Boolean) return value
    String text = value.toString().trim()
    return text.equalsIgnoreCase('true') || text.equalsIgnoreCase('y')
}

Timestamp timestampValue(Object value) {
    if (!value) return null
    if (value instanceof Timestamp) return (Timestamp) value
    if (value instanceof java.util.Date) return new Timestamp(((java.util.Date) value).time)
    String text = value.toString().trim()
    if (!text) return null
    if (text.length() == 10) text = "${text} 00:00:00"
    return Timestamp.valueOf(text.replace('T', ' ').take(19))
}

boolean withinDateRange(Object dateValue, Object fromDate, Object thruDate) {
    Timestamp valueTs = timestampValue(dateValue) ?: new Timestamp(0)
    Timestamp fromTs = timestampValue(fromDate)
    Timestamp thruTs = timestampValue(thruDate)
    if (fromTs && valueTs.before(fromTs)) return false
    if (thruTs) {
        Timestamp endTs = thruTs.toString().endsWith('00:00:00.0') ?
                new Timestamp(thruTs.time + (24L * 60L * 60L * 1000L) - 1L) : thruTs
        if (valueTs.after(endTs)) return false
    }
    return true
}

Map receiveMaterialInventory() {
    ExecutionContext ec = ec()
    String productId = (String) ctx('productId')
    String facilityId = (String) ctx('facilityId')
    BigDecimal quantity = bd(ctx('quantity'))
    BigDecimal unitCost = bd(ctx('unitCost'))

    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError('La cantidad recibida debe ser mayor que cero')
        return [:]
    }
    if (unitCost.compareTo(BigDecimal.ZERO) < 0) {
        ec.message.addError('El costo unitario no puede ser negativo')
        return [:]
    }

    Map params = [
            productId      : productId,
            facilityId     : facilityId,
            quantity       : quantity,
            acquireCost    : unitCost,
            acquireCostUomId: 'USD',
            ownerPartyId   : ctx('ownerPartyId'),
            locationSeqId  : ctx('locationSeqId'),
            receivedDate   : ctx('receivedDate'),
            workEffortId   : ctx('workEffortId')
    ].findAll { it.value != null && it.value != '' }

    Map receiveResult = ec.service.sync().name('mantle.product.AssetServices.receive#Asset')
            .parameters(params).call()
    if (ec.message.hasError()) return receiveResult ?: [:]

    // Costeo a promedio ponderado: preserva el costo real del lote y re-estampa todos los lotes
    // disponibles del producto al nuevo promedio para que el valor físico cuadre con la valuación.
    String ownerPartyId = (String) ctx('ownerPartyId')
    preserveOriginalAcquireCost(ec, receiveResult.assetId as String, productId, unitCost, 'USD', ctx('receivedDate'))
    ec.service.sync().name('sv.localization.InventorySvServices.recost#ProductToAverage')
            .parameters([productId: productId, ownerPartyId: ownerPartyId].findAll { it.value != null && it.value != '' })
            .call()

    return [
            assetId         : receiveResult.assetId,
            assetReceiptId  : receiveResult.assetReceiptId,
            assetDetailId   : receiveResult.assetDetailId,
            productId       : productId,
            facilityId      : facilityId,
            locationSeqId   : ctx('locationSeqId'),
            quantityAccepted: quantity,
            unitCost        : unitCost
    ]
}

Map moveInventory() {
    ExecutionContext ec = ec()
    String assetId = (String) ctx('assetId')
    String toFacilityId = (String) ctx('toFacilityId')
    BigDecimal requestedQuantity = bd(ctx('quantity'), null)

    def asset = ec.entity.find('mantle.product.asset.Asset')
            .condition('assetId', assetId).one()
    if (!asset) {
        ec.message.addError("Inventario ${assetId} no encontrado")
        return [:]
    }

    BigDecimal quantityToMove = requestedQuantity ?: bd(asset.quantityOnHandTotal)
    if (quantityToMove.compareTo(BigDecimal.ZERO) <= 0) {
        ec.message.addError('La cantidad a trasladar debe ser mayor que cero')
        return [:]
    }

    Map moveResult = ec.service.sync().name('mantle.product.AssetServices.move#Asset').parameters([
            assetId       : assetId,
            facilityId    : toFacilityId,
            locationSeqId : ctx('toLocationSeqId'),
            quantity      : quantityToMove,
            forceNewAsset : boolValue(ctx('forceNewAsset'), true)
    ].findAll { it.value != null && it.value != '' }).call()
    if (ec.message.hasError()) return moveResult ?: [:]

    String movedAssetId = findMovedAssetId(ec, asset, toFacilityId, (String) ctx('toLocationSeqId'), quantityToMove) ?: assetId
    // Preserva el costo histórico en el lote destino: hereda el original del lote origen
    // (o su acquireCost si el origen no tenía registro) para que el Kardex no pierda la historia.
    if (movedAssetId && movedAssetId != assetId) {
        def sourceOriginal = ec.entity.find('sv.localization.inventory.SvAssetAcquireCost')
                .condition('assetId', assetId).one()
        BigDecimal originalCost = sourceOriginal ? bd(sourceOriginal.originalAcquireCost) : bd(asset.acquireCost)
        preserveOriginalAcquireCost(ec, movedAssetId, asset.productId as String, originalCost,
                asset.acquireCostUomId as String, asset.receivedDate)
    }
    return [
            assetId        : assetId,
            movedAssetId   : movedAssetId,
            fromFacilityId : asset.facilityId,
            toFacilityId   : toFacilityId,
            quantityMoved  : quantityToMove
    ]
}

String findMovedAssetId(ExecutionContext ec, def sourceAsset, String toFacilityId, String toLocationSeqId,
                        BigDecimal quantityMoved) {
    def find = ec.entity.find('mantle.product.asset.Asset')
            .condition('assetId', 'not-equals', sourceAsset.assetId)
            .condition([productId: sourceAsset.productId, ownerPartyId: sourceAsset.ownerPartyId,
                        statusId: sourceAsset.statusId, facilityId: toFacilityId])
            .condition('quantityOnHandTotal', 'greater-equals', quantityMoved)
    if (toLocationSeqId) find.condition('locationSeqId', toLocationSeqId)
    if (sourceAsset.lotId) find.condition('lotId', sourceAsset.lotId)
    if (sourceAsset.receivedDate) find.condition('receivedDate', sourceAsset.receivedDate)
    if (sourceAsset.acquireCost != null) find.condition('acquireCost', sourceAsset.acquireCost)
    def movedAsset = find.orderBy('-lastUpdatedStamp,-assetId').one()
    movedAsset?.assetId as String
}

Map calculateAverageCost() {
    ExecutionContext ec = ec()
    String productId = (String) ctx('productId')
    String ownerPartyId = (String) ctx('ownerPartyId')
    String facilityId = (String) ctx('facilityId')

    def find = ec.entity.find('mantle.product.asset.Asset')
            .condition([productId: productId, statusId: 'AstAvailable'])
            .condition('quantityOnHandTotal', 'greater', BigDecimal.ZERO)
    if (ownerPartyId) find.condition('ownerPartyId', ownerPartyId)
    if (facilityId) find.condition('facilityId', facilityId)

    BigDecimal quantityOnHand = BigDecimal.ZERO
    BigDecimal inventoryValue = BigDecimal.ZERO
    Integer assetCount = 0
    String costUomId = 'USD'

    find.list().each { asset ->
        if (asset.acquireCost == null) return
        BigDecimal quantity = bd(asset.quantityOnHandTotal)
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) return
        BigDecimal unitCost = bd(asset.acquireCost)
        quantityOnHand += quantity
        inventoryValue += quantity * unitCost
        assetCount++
        if (asset.acquireCostUomId) costUomId = asset.acquireCostUomId
    }

    BigDecimal averageCost = quantityOnHand.compareTo(BigDecimal.ZERO) > 0 ?
            inventoryValue.divide(quantityOnHand, 6, RoundingMode.HALF_UP).stripTrailingZeros() : BigDecimal.ZERO

    Map kardex = ec.service.sync().name('sv.localization.InventorySvServices.get#ProductKardex')
            .parameters([productId: productId, ownerPartyId: ownerPartyId, facilityId: facilityId]
                    .findAll { it.value != null && it.value != '' }).call()
    if ((kardex?.kardexRows ?: [])) {
        averageCost = bd(kardex.currentAvgCost)
        quantityOnHand = bd(kardex.balanceQty)
        inventoryValue = bd(kardex.balanceValue)
    }

    return [
            productId      : productId,
            ownerPartyId   : ownerPartyId,
            facilityId     : facilityId,
            averageCost    : averageCost,
            quantityOnHand : quantityOnHand,
            inventoryValue : inventoryValue,
            assetCount     : assetCount,
            costUomId      : costUomId
    ]
}

String userLabel(ExecutionContext ec, Map<String, String> cache, String userId) {
    if (!userId) return ''
    if (cache.containsKey(userId)) return cache[userId]
    def ua = ec.entity.find('moqui.security.UserAccount').condition('userId', userId).one()
    String label = (ua?.userFullName ?: ua?.username ?: userId) as String
    cache[userId] = label
    return label
}

String partyLabel(ExecutionContext ec, Map<String, String> cache, String partyId) {
    if (!partyId) return ''
    if (cache.containsKey(partyId)) return cache[partyId]
    def pd = ec.entity.find('mantle.party.PartyDetail').condition('partyId', partyId).one()
    String personName = pd ? [pd.firstName, pd.lastName].findAll { it }.join(' ') : ''
    String label = (personName ?: pd?.organizationName ?: partyId) as String
    cache[partyId] = label
    return label
}

String movementTypeFor(def detail) {
    if (detail.assetReceiptId) return 'Entrada'
    if (detail.assetIssuanceId || detail.workEffortId) return 'Salida'
    if (detail.shipmentId || detail.orderId) return 'Despacho'
    if (detail.physicalInventoryId) return 'Ajuste'
    return bd(detail.quantityOnHandDiff) >= BigDecimal.ZERO ? 'Entrada' : 'Salida'
}

String referenceFor(def detail) {
    if (detail.assetReceiptId) return "Recibo ${detail.assetReceiptId}"
    if (detail.workEffortId) return "Orden ${detail.workEffortId}"
    if (detail.assetIssuanceId) return "Egreso ${detail.assetIssuanceId}"
    if (detail.shipmentId) return "Envio ${detail.shipmentId}"
    if (detail.orderId) return "Pedido ${detail.orderId}"
    if (detail.physicalInventoryId) return "Inv. fisico ${detail.physicalInventoryId}"
    return ''
}

Map getProductKardex() {
    ExecutionContext ec = ec()
    String productId = (String) ctx('productId')
    String ownerPartyId = (String) ctx('ownerPartyId')
    String facilityId = (String) ctx('facilityId')

    // Activos del producto (acotados por propietario/bodega) para resolver los movimientos.
    def assetFind = ec.entity.find('mantle.product.asset.Asset')
            .condition('productId', productId)
    if (ownerPartyId) assetFind.condition('ownerPartyId', ownerPartyId)
    if (facilityId) assetFind.condition('facilityId', facilityId)
    // El costo de adquisición vive en Asset.acquireCost (AssetDetail.unitCost suele venir vacío
    // en recepciones), así que mapeamos costo por activo para valuar las entradas. Bajo costeo a
    // promedio, Asset.acquireCost se re-estampa al promedio, por lo que preferimos el costo
    // histórico inmutable preservado en SvAssetAcquireCost cuando existe.
    List assetList = assetFind.list()
    Map<String, BigDecimal> acquireCostByAsset = [:]
    List<String> assetIds = assetList.collect { asset ->
        acquireCostByAsset[asset.assetId as String] = bd(asset.acquireCost)
        asset.assetId as String
    }
    if (!assetIds) {
        return [productId: productId, ownerPartyId: ownerPartyId, facilityId: facilityId,
                kardexRows: [], currentAvgCost: BigDecimal.ZERO,
                balanceQty: BigDecimal.ZERO, balanceValue: BigDecimal.ZERO]
    }

    // Costo histórico inmutable por lote (sobrevive al re-estampado a promedio de Asset.acquireCost).
    Map<String, BigDecimal> originalCostByAsset = [:]
    ec.entity.find('sv.localization.inventory.SvAssetAcquireCost')
            .condition('assetId', 'in', assetIds).list().each { row ->
        if (row.originalAcquireCost != null) originalCostByAsset[row.assetId as String] = bd(row.originalAcquireCost)
    }

    def detailFind = ec.entity.find('mantle.product.asset.AssetDetail')
            .condition('assetId', 'in', assetIds)
    if (ctx('fromDate')) detailFind.condition('effectiveDate', 'greater-equals', ctx('fromDate'))
    if (ctx('thruDate')) detailFind.condition('effectiveDate', 'less-equals', ctx('thruDate'))

    List detailList = detailFind.orderBy('effectiveDate,assetDetailId').list()
    Set<String> assetsWithDetails = detailList.collect { it.assetId as String } as Set
    List movementList = detailList.collect { [kind: 'detail', effectiveDate: it.effectiveDate, value: it] }
    assetList.each { asset ->
        String assetId = asset.assetId as String
        if (assetsWithDetails.contains(assetId)) return
        BigDecimal initialQty = bd(asset.quantityOnHandTotal)
        if (initialQty.compareTo(BigDecimal.ZERO) <= 0) return
        Object receivedDate = asset.receivedDate ?: asset.createdStamp
        if (!withinDateRange(receivedDate, ctx('fromDate'), ctx('thruDate'))) return
        movementList.add([kind: 'initial', effectiveDate: receivedDate ?: new Timestamp(0), value: asset])
    }
    movementList = movementList.sort { a, b ->
        timestampValue(a.effectiveDate).time <=> timestampValue(b.effectiveDate).time ?:
                (a.value.assetId as String) <=> (b.value.assetId as String)
    }

    // "Quién hizo el movimiento": el recibo guarda receivedByUserId, el egreso issuedByUserId
    // (Mantle los llena con ec.user.userId) y el conteo físico guarda partyId. Pre-cargamos los
    // ids en lote para no consultar por fila, y resolvemos userId/partyId a un nombre legible.
    Set<String> receiptIds = detailList.findResults { it.assetReceiptId as String ?: null } as Set
    Set<String> issuanceIds = detailList.findResults { it.assetIssuanceId as String ?: null } as Set
    Set<String> physInvIds = detailList.findResults { it.physicalInventoryId as String ?: null } as Set

    Map<String, String> userByReceipt = [:]
    if (receiptIds) ec.entity.find('mantle.product.receipt.AssetReceipt')
            .condition('assetReceiptId', 'in', receiptIds).list().each { r ->
        if (r.receivedByUserId) userByReceipt[r.assetReceiptId as String] = r.receivedByUserId as String
    }
    Map<String, String> userByIssuance = [:]
    if (issuanceIds) ec.entity.find('mantle.product.issuance.AssetIssuance')
            .condition('assetIssuanceId', 'in', issuanceIds).list().each { i ->
        if (i.issuedByUserId) userByIssuance[i.assetIssuanceId as String] = i.issuedByUserId as String
    }
    Map<String, String> partyByPhysInv = [:]
    if (physInvIds) ec.entity.find('mantle.product.asset.PhysicalInventory')
            .condition('physicalInventoryId', 'in', physInvIds).list().each { p ->
        if (p.partyId) partyByPhysInv[p.physicalInventoryId as String] = p.partyId as String
    }
    Map<String, String> userLabelCache = [:]
    Map<String, String> partyLabelCache = [:]

    BigDecimal balanceQty = BigDecimal.ZERO
    BigDecimal balanceValue = BigDecimal.ZERO
    BigDecimal avgCost = BigDecimal.ZERO
    List kardexRows = []

    movementList.each { movement ->
        def detail = movement.value
        boolean isInitial = movement.kind == 'initial'
        BigDecimal qtyDiff = isInitial ? bd(detail.quantityOnHandTotal) : bd(detail.quantityOnHandDiff)
        if (qtyDiff.compareTo(BigDecimal.ZERO) == 0) return
        BigDecimal qtyIn = BigDecimal.ZERO
        BigDecimal qtyOut = BigDecimal.ZERO
        BigDecimal lineUnitCost

        if (qtyDiff.compareTo(BigDecimal.ZERO) > 0) {
            // Entrada: valuada al costo histórico del lote (preferimos el original preservado, luego
            // el unitCost del detalle, luego el acquireCost actual), recalcula promedio móvil.
            qtyIn = qtyDiff
            String dAssetId = detail.assetId as String
            BigDecimal originalCost = originalCostByAsset[dAssetId]
            BigDecimal detailCost = isInitial ? BigDecimal.ZERO : bd(detail.unitCost)
            lineUnitCost = originalCost != null ? originalCost :
                    (detailCost.compareTo(BigDecimal.ZERO) > 0 ? detailCost :
                            (acquireCostByAsset[dAssetId] ?: BigDecimal.ZERO))
            balanceValue += qtyIn * lineUnitCost
            balanceQty += qtyIn
        } else {
            // Salida: valuada al promedio vigente; el promedio no cambia. Se conserva el valor
            // de forma proporcional para evitar deriva por redondeo del promedio.
            qtyOut = qtyDiff.negate()
            lineUnitCost = avgCost
            BigDecimal newBalanceQty = balanceQty - qtyOut
            balanceValue = newBalanceQty.compareTo(BigDecimal.ZERO) > 0 ?
                    balanceValue.multiply(newBalanceQty).divide(balanceQty, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO
            balanceQty = newBalanceQty
        }

        avgCost = balanceQty.compareTo(BigDecimal.ZERO) > 0 ?
                balanceValue.divide(balanceQty, 6, RoundingMode.HALF_UP).stripTrailingZeros() : BigDecimal.ZERO

        String movedBy = ''
        if (!isInitial && detail.assetReceiptId) movedBy = userLabel(ec, userLabelCache, userByReceipt[detail.assetReceiptId as String])
        else if (!isInitial && detail.assetIssuanceId) movedBy = userLabel(ec, userLabelCache, userByIssuance[detail.assetIssuanceId as String])
        else if (!isInitial && detail.physicalInventoryId) movedBy = partyLabel(ec, partyLabelCache, partyByPhysInv[detail.physicalInventoryId as String])

        kardexRows.add([
                effectiveDate: isInitial ? movement.effectiveDate : detail.effectiveDate,
                movementType : isInitial ? 'Saldo inicial' : movementTypeFor(detail),
                reference    : isInitial ? 'Inventario inicial' : referenceFor(detail),
                movedBy      : movedBy,
                qtyIn        : qtyIn,
                qtyOut       : qtyOut,
                unitCost     : lineUnitCost,
                avgCost      : avgCost,
                balanceQty   : balanceQty,
                balanceValue : balanceValue
        ])
    }

    return [
            productId     : productId,
            ownerPartyId  : ownerPartyId,
            facilityId    : facilityId,
            kardexRows    : kardexRows,
            currentAvgCost: avgCost,
            balanceQty    : balanceQty,
            balanceValue  : balanceValue
    ]
}

void preserveOriginalAcquireCost(ExecutionContext ec, String assetId, String productId,
                                 BigDecimal originalCost, String costUomId, Object receivedDate) {
    if (!assetId) return
    def existing = ec.entity.find('sv.localization.inventory.SvAssetAcquireCost')
            .condition('assetId', assetId).one()
    if (existing) return
    ec.entity.makeValue('sv.localization.inventory.SvAssetAcquireCost').setAll([
            assetId            : assetId,
            productId          : productId,
            originalAcquireCost: originalCost,
            acquireCostUomId   : costUomId ?: 'USD',
            receivedDate       : receivedDate
    ]).create()
}

Map recostProductToAverage() {
    ExecutionContext ec = ec()
    String productId = (String) ctx('productId')
    String ownerPartyId = (String) ctx('ownerPartyId')

    def find = ec.entity.find('mantle.product.asset.Asset')
            .condition([productId: productId, statusId: 'AstAvailable'])
            .condition('quantityOnHandTotal', 'greater', BigDecimal.ZERO)
    if (ownerPartyId) find.condition('ownerPartyId', ownerPartyId)

    List assets = find.list()
    BigDecimal totalQty = BigDecimal.ZERO
    BigDecimal totalValue = BigDecimal.ZERO
    String costUomId = 'USD'
    assets.each { asset ->
        if (asset.acquireCost == null) return
        BigDecimal qty = bd(asset.quantityOnHandTotal)
        if (qty.compareTo(BigDecimal.ZERO) <= 0) return
        totalQty += qty
        totalValue += qty * bd(asset.acquireCost)
        if (asset.acquireCostUomId) costUomId = asset.acquireCostUomId
    }

    if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
        return [productId: productId, ownerPartyId: ownerPartyId, averageCost: BigDecimal.ZERO,
                quantityOnHand: BigDecimal.ZERO, restampedCount: 0]
    }

    BigDecimal averageCost = totalValue.divide(totalQty, 6, RoundingMode.HALF_UP).stripTrailingZeros()

    // Re-estampa todos los lotes disponibles al promedio para que el valor físico del
    // inventario coincida con la valuación a promedio ponderado. El costo real de cada lote
    // queda preservado en SvAssetAcquireCost para el Kardex.
    int restamped = 0
    assets.each { asset ->
        if (asset.acquireCost == null) return
        if (bd(asset.acquireCost).compareTo(averageCost) != 0) {
            asset.acquireCost = averageCost
            asset.acquireCostUomId = costUomId
            asset.update()
            restamped++
        }
    }

    return [productId: productId, ownerPartyId: ownerPartyId, averageCost: averageCost,
            quantityOnHand: totalQty, restampedCount: restamped]
}
