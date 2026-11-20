# Framework-aware Java refactoring

Authority: the [ARC42 requirements appendix](arc42/appendix-requirements.adoc)
owns integrated status and priority. This satellite describes the source-level
detector, a narrow compiler-backed reference-analysis slice, and their limits;
the P1 IDE Java backlog tracks the remaining framework-reference work. The
detector does not provide semantic reference closure or Spring Boot/Jakarta/JPA
navigation and inspections beyond ordinary Java/JDT source semantics.

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

Entity-field rename also uses `JavaFrameworkReferenceAnalyzer` for a narrow
Spring Data query cell. For an owner with one uniquely named `@Entity`, it finds
direct, escape-free `@Query` string literals whose JPQL has one entity root and
an unambiguous alias, then records exact `alias.property` source ranges for the
selected entity property. These edits appear in the rename preview alongside
JDT-bound Java edits.

The plan exposes a typed `PlanReferenceAnalysis` with reference kind, target
owner/member, source location, resource kind, provenance and completeness. The
analysis always reports `completeProject: false`; the plan is HIGH risk and
`PatchEngine` refuses managed Apply while reference closure is incomplete.
Exact locations are evidence for the supported literal paths, not proof that
all consumers were found.

Daemon `refactor.preview` exposes this report as `referenceAnalysis`; it adds
`managedWriteEligibility: INELIGIBLE` when typed-reference closure is incomplete.
Reference locations use zero-based line and character coordinates. IDE consumers
must preserve the evidence and disable managed Apply; the field does not grant
mutation authority.

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
- Query reference support is limited to direct, escape-free Spring Data
  `@Query` string literals with one recognized JPQL entity root and alias.
  Dynamic/constant-expression values, multiple roots, explicit entity-name
  mappings, `@NamedQuery`, other persistence mappings, XML/YAML/properties,
  migrations and generated metamodels remain unresolved or outside the scanned
  scope. They keep entity-field rename at HIGH risk with managed Apply disabled.
- A clean JDT reference set is not a complete Spring/JPA reference set for
  these operations.
- It intentionally skips annotations inside comments, string literals, character
  literals, and Java text blocks.

## Tests

`JavaFrameworkDetectorTest` covers:

- Spring/JPA/Jackson annotation detection
- skipping annotations in comments, strings, and text blocks
- risk escalation and warnings in rename/move/safe-delete planners
- low-risk behavior for unannotated classes
