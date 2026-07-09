# Framework-aware Java refactoring

RefactorKit performs source-level framework annotation detection to improve safety
for Java refactorings. The detector does **not** require Spring, JPA, Jakarta
Persistence, or Jackson dependencies on the classpath.

## Detector

Implemented in:

```text
modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaFrameworkDetector.kt
```

Main types:

- `JavaFramework` — `SPRING`, `JPA`, `JACKSON`
- `JavaFrameworkFinding` — annotation occurrence with path and line
- `JavaFrameworkAssessment` — aggregate findings and operation-specific warnings
- `JavaFrameworkDetector` — lexical scanner that skips Java comments, strings,
  character literals, and text blocks

## Recognized annotations

Spring:

```text
@Component @Service @Repository @Controller @RestController
@Configuration @Bean @Autowired @Qualifier @RequestMapping
@GetMapping @PostMapping @PutMapping @DeleteMapping @PatchMapping
@ConfigurationProperties
```

JPA / Jakarta Persistence:

```text
@Entity @Table @Column @Id @GeneratedValue
@OneToMany @ManyToOne @OneToOne @ManyToMany
@JoinColumn @JoinTable @Embeddable @Embedded
@MappedSuperclass @Transient @Version
```

Jackson:

```text
@JsonProperty @JsonTypeName @JsonSubTypes @JsonTypeInfo
@JsonCreator @JsonIgnore @JsonAlias @JsonValue
@JsonDeserialize @JsonSerialize
```

Fully qualified annotations such as `@org.springframework.stereotype.Service` are
recognized by simple annotation name.

## Planner integration

Framework findings are integrated into:

- `JavaRenameClassPlanner`
- `JavaMoveClassPlanner`
- `JavaSafeDeletePlanner`

When findings exist on the declaration file:

- `riskLevel` is escalated to `HIGH`
- operation-specific warnings are added
- annotation locations are reported as `path:line @Annotation`

Examples:

- Rename Spring service → warns about derived bean names, qualifiers, SpEL,
  XML/config, and tests.
- Move Spring component → warns about component scanning and package-based
  conditions.
- Rename JPA entity → warns about default entity names, JPQL strings, Criteria
  usage, repositories, and migrations.
- Move JPA entity → warns about entity scanning and persistence-unit config.
- Rename Jackson DTO/type → warns about serialized names and external API
  contracts.
- Safe delete framework-managed type → warns about reflection/config/serialization
  references that may not appear in Java source references.

## Limitations

- Detection is lexical, not compiler-backed.
- It does not resolve aliases or meta-annotations.
- It does not inspect XML, YAML, properties files, migrations, JPQL strings, or
  generated metamodels.
- It intentionally skips annotations inside comments, string literals, character
  literals, and Java text blocks.

## Tests

`JavaFrameworkDetectorTest` covers:

- Spring/JPA/Jackson annotation detection
- skipping annotations in comments, strings, and text blocks
- risk escalation and warnings in rename/move/safe-delete planners
- low-risk behavior for unannotated classes
