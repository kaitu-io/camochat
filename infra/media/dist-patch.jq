.DistributionConfig
| .WebACLId = $acl
| .Origins.Items = ([.Origins.Items[] | select(.Id != "s3-chencang-media" and .Id != "lambda-media-signer")] + [
    { Id: "s3-chencang-media", DomainName: $bd, OriginPath: "", CustomHeaders: {Quantity: 0},
      S3OriginConfig: {OriginAccessIdentity: ""}, OriginAccessControlId: $mo,
      ConnectionAttempts: 3, ConnectionTimeout: 10, OriginShield: {Enabled: false} },
    { Id: "lambda-media-signer", DomainName: $sd, OriginPath: "", CustomHeaders: {Quantity: 0},
      CustomOriginConfig: {HTTPPort: 80, HTTPSPort: 443, OriginProtocolPolicy: "https-only",
        OriginSslProtocols: {Quantity: 1, Items: ["TLSv1.2"]}, OriginReadTimeout: 10, OriginKeepaliveTimeout: 5},
      OriginAccessControlId: $so, ConnectionAttempts: 3, ConnectionTimeout: 10, OriginShield: {Enabled: false} }
  ])
| .Origins.Quantity = (.Origins.Items | length)
| def beh($p; $origin; $cache; $methods; $fn; $rhp):
    { PathPattern: $p, TargetOriginId: $origin, ViewerProtocolPolicy: "redirect-to-https",
      AllowedMethods: {Quantity: ($methods|length), Items: $methods, CachedMethods: {Quantity: 2, Items: ["GET","HEAD"]}},
      Compress: false, SmoothStreaming: false, FieldLevelEncryptionId: "",
      CachePolicyId: $cache,
      ResponseHeadersPolicyId: $rhp,
      OriginRequestPolicyId: (if $origin == "lambda-media-signer" then "b689b0a8-53d0-40ab-baf2-68738e2966ac" else null end),
      TrustedSigners: {Enabled: false, Quantity: 0}, TrustedKeyGroups: {Enabled: false, Quantity: 0},
      LambdaFunctionAssociations: {Quantity: 0},
      FunctionAssociations: (if $fn then {Quantity: 1, Items: [{EventType: "viewer-request", FunctionARN: $fn}]} else {Quantity: 0} end) }
    | with_entries(select(.value != null));
  .CacheBehaviors.Items = ([(.CacheBehaviors.Items // [])[] | select(.PathPattern | IN("/b/*","/api/upload","/m/*","/p","/p/*","/source*") | not)] + [
    beh("/api/upload"; "lambda-media-signer"; "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"; ["GET","HEAD"]; null; null),
    beh("/b/*";        "s3-chencang-media";   $cp; ["GET","HEAD"]; null; $rhp),
    beh("/m/*";        "s3-chencang-site";    "658327ea-f89d-4fab-a63d-7e88639e58f6"; ["GET","HEAD"]; $fn; null),
    beh("/p";          "s3-chencang-site";    "658327ea-f89d-4fab-a63d-7e88639e58f6"; ["GET","HEAD"]; $fn; null),
    beh("/p/*";        "s3-chencang-site";    "658327ea-f89d-4fab-a63d-7e88639e58f6"; ["GET","HEAD"]; $fn; null),
    beh("/source*";    "s3-chencang-site";    "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"; ["GET","HEAD"]; $fn; null)
  ])
| .CacheBehaviors.Quantity = (.CacheBehaviors.Items | length)
