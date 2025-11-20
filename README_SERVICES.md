# Capa de Lógica de Negocio - Arquitectura Hexagonal

## 📁 Estructura de la Capa de Aplicación

La lógica de negocio se implementa en la capa `application`, específicamente en:

```
application/
├── service/          # ✅ AQUÍ VA TU LÓGICA DE NEGOCIO
├── port/            # Interfaces (contratos) para repositorios
├── mapper/          # Conversión entre DTOs, Domain y Entities
├── dto/             # DTOs de Request y Response
└── exception/       # Excepciones personalizadas
```

## 🎯 Dónde Implementar la Lógica de Negocio

### 1. **Servicios (`application/service/`)**

Aquí es donde implementas **TODA tu lógica de negocio específica**:

- ✅ Validaciones de negocio
- ✅ Reglas de negocio
- ✅ Cálculos complejos
- ✅ Orquestación de operaciones
- ✅ Validación de estados y transiciones
- ✅ Generación de códigos únicos
- ✅ Validación de disponibilidad de recursos

**Ejemplo: `UserService.java`**
```java
@Service
public class UserService {
    
    public UserResponse createUser(UserRequest request) {
        // ✅ Lógica de negocio: Validar username único
        if (userRepositoryPort.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists");
        }
        
        // ✅ Lógica de negocio: Establecer valores por defecto
        if (request.getStatus() == null) {
            request.setStatus("active");
        }
        
        // ✅ Lógica de negocio: Encriptar password
        // user.setPassword(passwordEncoder.encode(request.getPassword()));
        
        // Guardar
        User saved = userRepositoryPort.save(user);
        return mapper.toResponse(saved);
    }
}
```

### 2. **Puertos (`application/port/`)**

Interfaces que definen contratos sin conocer la implementación:

```java
public interface UserRepositoryPort {
    User save(User user);
    Optional<User> findById(Long id);
    // ...
}
```

### 3. **Adaptadores (`infrastructure/persistence/adapter/`)**

Implementan los puertos usando JPA:

```java
@Component
public class UserRepositoryAdapter implements UserRepositoryPort {
    private final UserRepository userRepository; // JPA Repository
    private final UserMapper mapper;
    
    @Override
    public User save(User user) {
        UserEntity entity = mapper.toEntity(user);
        return mapper.toDomain(userRepository.save(entity));
    }
}
```

## 📝 Ejemplos de Lógica de Negocio

### Ejemplo 1: Validaciones de Negocio
```java
public ProductResponse createProduct(ProductRequest request) {
    // Validar código único
    if (productRepositoryPort.existsByCode(request.getCode())) {
        throw new BusinessException("Product code already exists");
    }
    
    // Validar que categoría exista
    if (!categoryRepositoryPort.existsById(request.getCategoryId())) {
        throw new ResourceNotFoundException("Category", request.getCategoryId());
    }
    
    // Lógica de negocio: Calcular tiempo de producción
    if (request.getPrdTime() == null) {
        request.setPrdTime(calculateDefaultProductionTime(request));
    }
    
    return mapper.toResponse(productRepositoryPort.save(product));
}
```

### Ejemplo 2: Reglas de Estado
```java
public ProductionOrderResponse changeOrderStatus(Long id, String newStatus) {
    ProductionOrder order = findById(id);
    
    // Validar transición de estado válida
    validateStatusTransition(order.getStatus(), newStatus);
    
    // Si se completa, actualizar inventario
    if ("completed".equals(newStatus)) {
        updateInventory(order);
    }
    
    order.setStatus(newStatus);
    return mapper.toResponse(orderRepositoryPort.save(order));
}
```

### Ejemplo 3: Cálculos Complejos
```java
public ProductionOrderResponse createOrder(ProductionOrderRequest request) {
    // Validar disponibilidad de materiales
    validateMaterialAvailability(request.getProductId(), request.getQuantity());
    
    // Generar código único
    String code = generateOrderCode("PO");
    
    // Calcular tiempo estimado
    Double estimatedTime = calculateEstimatedTime(product, quantity);
    
    // Crear orden
    ProductionOrder order = mapper.toDomain(request);
    order.setCode(code);
    order.setEstimatedTime(estimatedTime);
    
    return mapper.toResponse(orderRepositoryPort.save(order));
}
```

## 🔄 Flujo de Datos

```
Controller (REST)
    ↓
Service (Lógica de Negocio) ← ✅ AQUÍ IMPLEMENTAS TU LÓGICA
    ↓
Port (Interfaz)
    ↓
Adapter (Implementación)
    ↓
Repository (JPA)
    ↓
Database
```

## ✅ Buenas Prácticas

1. **Toda la lógica de negocio va en los Services**
2. **Los Services usan Ports, no Repositories directamente**
3. **Los Mappers convierten entre capas**
4. **Las excepciones personalizadas para errores de negocio**
5. **Validaciones de negocio separadas de validaciones de DTOs**

## 🚀 Próximos Pasos

1. Crear servicios para cada entidad principal
2. Implementar la lógica de negocio específica
3. Crear los mappers correspondientes
4. Crear los adaptadores para los repositorios
5. Implementar los controladores REST

