package io.casehub.ledger.examples.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "orders")
public class Order extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public String customerId;

    @Column(nullable = false)
    public BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OrderStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
        if (createdAt == null)
            createdAt = Instant.now();
        if (status == null)
            status = OrderStatus.PENDING;
    }
}
