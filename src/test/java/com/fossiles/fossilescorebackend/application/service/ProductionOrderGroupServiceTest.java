package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderGroupRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderGroupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupMemberEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupMemberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OP consolidada: quién entra, el código, la suma de productos y desagrupar. */
class ProductionOrderGroupServiceTest {

    private final ProductionOrderGroupRepository groupRepository = mock(ProductionOrderGroupRepository.class);
    private final ProductionOrderGroupMemberRepository memberRepository = mock(ProductionOrderGroupMemberRepository.class);
    private final ProductionOrderRepository orderRepository = mock(ProductionOrderRepository.class);
    private final ProductionOrderItemRepository itemRepository = mock(ProductionOrderItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ColorRepository colorRepository = mock(ColorRepository.class);
    private final ProductionOrderGroupService service = new ProductionOrderGroupService(groupRepository, memberRepository,
            orderRepository, itemRepository, productRepository, colorRepository, mock(SecurityUtil.class));

    private final Map<Long, ProductionOrderEntity> orders = new HashMap<>();
    private final Map<Long, List<ProductionOrderItemEntity>> items = new HashMap<>();
    private final List<ProductionOrderGroupMemberEntity> members = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(orderRepository.findAllById(any())).thenAnswer(inv -> StreamSupport
                .stream(((Iterable<Long>) inv.getArgument(0)).spliterator(), false)
                .map(orders::get).filter(Objects::nonNull).toList());
        when(itemRepository.findByProductionOrderId(anyLong()))
                .thenAnswer(inv -> items.getOrDefault(inv.<Long>getArgument(0), List.of()));
        when(memberRepository.findByProductionOrderIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return members.stream().filter(m -> ids.contains(m.getProductionOrderId())).toList();
        });
        when(memberRepository.save(any())).thenAnswer(inv -> {
            members.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(groupRepository.save(any())).thenAnswer(inv -> {
            ProductionOrderGroupEntity g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(7L);
            }
            return g;
        });
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(10L).code("P-10").name("Billetera").build()));
        when(colorRepository.findAllById(any())).thenReturn(List.of());
    }

    private ProductionOrderEntity order(long id, String code, String type, String seller, String status) {
        ProductionOrderEntity o = ProductionOrderEntity.builder().id(id).code(code).orderType(type)
                .sellerName(seller).status(status).build();
        orders.put(id, o);
        return o;
    }

    private void item(long id, long orderId, int qty, String sizes) {
        items.computeIfAbsent(orderId, k -> new ArrayList<>()).add(ProductionOrderItemEntity.builder()
                .id(id).productionOrderId(orderId).productId(10L).quantity(qty).sizesData(sizes).build());
    }

    private static ProductionOrderGroupRequest req(Long... ids) {
        return ProductionOrderGroupRequest.builder().productionOrderIds(List.of(ids)).build();
    }

    @Test
    @DisplayName("Junta OPV de Luis Felipe: código LF-OPV-007 y productos sumados con su OP")
    void creaYSuma() throws Exception {
        order(1, "OPV-64", "NORMAL", "Luis Felipe", "PENDING");
        order(2, "OPV-65", "MARCAS", "LUIS FELIPE", "IN_PROGRESS");
        item(11, 1, 3, null);
        item(21, 2, 2, null);

        ProductionOrderGroupResponse g = service.create(req(2L, 1L));

        assertThat(g.getCode()).isEqualTo("LF-OPV-007");
        assertThat(g.getFamily()).isEqualTo("OPV");
        assertThat(g.getOrders()).extracting("code").containsExactly("OPV-64", "OPV-65");
        assertThat(g.getTotalUnits()).isEqualTo(5);
        assertThat(g.getLines()).hasSize(1);
        assertThat(g.getLines().get(0).getQuantity()).isEqualTo(5);
        assertThat(g.getLines().get(0).getByOrder()).containsEntry("OPV-64", 3).containsEntry("OPV-65", 2);
        assertThat(members).extracting("productionOrderId").containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("No mezcla OPV con OPC, ni órdenes de otro vendedor, ni cerradas")
    void reglasDeEntrada() {
        order(1, "OPV-64", "NORMAL", "Luis Felipe", "PENDING");
        order(2, "OPC-136", "CINCHOS_FOSSILES", "Luis Felipe", "PENDING");
        order(3, "OPV-90", "MARCAS", "Otro", "PENDING");
        order(4, "OPV-66", "NORMAL", "Luis Felipe", "COMPLETED");

        assertThatThrownBy(() -> service.create(req(1L, 2L))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No se mezclan OPV y OPC");
        assertThatThrownBy(() -> service.create(req(1L, 3L))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPV-90 no lo es");
        assertThatThrownBy(() -> service.create(req(1L, 4L))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPV-66 ya está COMPLETED");
        assertThatThrownBy(() -> service.create(req(1L))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("al menos dos");
        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una OP no puede estar en dos consolidadas")
    void unaSolaConsolidada() {
        order(1, "OPV-64", "NORMAL", "Luis Felipe", "PENDING");
        order(2, "OPV-65", "NORMAL", "Luis Felipe", "PENDING");
        members.add(ProductionOrderGroupMemberEntity.builder().groupId(3L).productionOrderId(2L).build());
        when(groupRepository.findAllById(any())).thenReturn(List.of(
                ProductionOrderGroupEntity.builder().id(3L).code("LF-OPV-003").build()));

        assertThatThrownBy(() -> service.create(req(1L, 2L))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPV-65 (en LF-OPV-003)");
    }

    @Test
    @DisplayName("Cinchos: suma tallas de varias OPC")
    void sumaTallasDeCinchos() throws Exception {
        order(1, "OPC-136", "CINCHOS_FOSSILES", "Luis Felipe", "PENDING");
        order(2, "OPC-137", "CINCHOS_FOSSILES", "Luis Felipe", "PENDING");
        item(11, 1, 0, "{\"32\":2,\"34\":1}");
        item(21, 2, 0, "{\"34\":3}");

        ProductionOrderGroupResponse g = service.create(req(1L, 2L));

        assertThat(g.getCode()).isEqualTo("LF-OPC-007");
        assertThat(g.getLines().get(0).getSizes()).containsEntry("32", 2).containsEntry("34", 4);
    }

    @Test
    @DisplayName("Desagrupar borra los miembros y deja la consolidada como DISSOLVED")
    void desagrupa() throws Exception {
        ProductionOrderGroupEntity g = ProductionOrderGroupEntity.builder().id(7L).code("LF-OPV-007")
                .family("OPV").status("ACTIVE").build();
        when(groupRepository.findById(7L)).thenReturn(Optional.of(g));
        when(memberRepository.findByGroupIdOrderByIdAsc(7L)).thenReturn(List.of());

        ProductionOrderGroupResponse out = service.dissolve(7L);

        verify(memberRepository).deleteByGroupId(7L);
        assertThat(out.getStatus()).isEqualTo("DISSOLVED");
        assertThatThrownBy(() -> service.dissolve(7L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya estaba desagrupada");
    }
}
