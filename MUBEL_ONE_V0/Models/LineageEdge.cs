namespace MubelOne.Models;

public sealed record LineageEdge(
    string DatabaseName,
    string? TransactionId,
    string Fingerprint,
    string Operation,
    string TargetObject,
    string SourceObject,
    DateTimeOffset ObservedAt,
    string EvidenceLevel,
    string EvidenceSource);
