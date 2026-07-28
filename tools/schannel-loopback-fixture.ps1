#requires -version 5
param(
  [Parameter(Mandatory = $true)]
  [string]$PfxBase64Path,
  [string]$Password = "jolt-test"
)

$ErrorActionPreference = "Stop"

$listener = $null
$certificate = $null
try {
  $base64 = [IO.File]::ReadAllText($PfxBase64Path)
  $bytes = [Convert]::FromBase64String($base64)
  $flags = [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable
  $certificate = New-Object `
    Security.Cryptography.X509Certificates.X509Certificate2(
      $bytes, $Password, $flags)

  $listener = New-Object Net.Sockets.TcpListener(
    [Net.IPAddress]::Loopback, 0)
  $listener.Start()
  $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
  [Console]::Out.WriteLine("PORT=$port")
  [Console]::Out.Flush()

  $outcomes = New-Object Collections.Generic.List[string]
  for ($attempt = 0; $attempt -lt 3; $attempt++) {
    $client = $listener.AcceptTcpClient()
    $tls = $null
    try {
      $tls = New-Object Net.Security.SslStream($client.GetStream(), $false)
      $tls.AuthenticateAsServer(
        $certificate,
        $false,
        [Security.Authentication.SslProtocols]::Tls12,
        $false)

      $request = New-Object IO.MemoryStream
      $buffer = New-Object byte[] 4096
      while ($request.Length -lt 65536) {
        $read = $tls.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) {
          break
        }
        $request.Write($buffer, 0, $read)
        $text = [Text.Encoding]::ASCII.GetString($request.ToArray())
        if ($text.Contains("`r`n`r`n")) {
          break
        }
      }
      $requestText = [Text.Encoding]::ASCII.GetString($request.ToArray())
      if (-not $requestText.StartsWith("GET /schannel HTTP/1.1`r`n")) {
        throw "unexpected HTTP request: $requestText"
      }

      $body = "schannel"
      $response = [Text.Encoding]::ASCII.GetBytes(
        "HTTP/1.1 200 OK`r`n" +
        "Content-Length: $($body.Length)`r`n" +
        "Connection: close`r`n" +
        "`r`n" +
        $body)
      $tls.Write($response, 0, $response.Length)
      $tls.Flush()
      # .NET 8 exposes an explicit TLS shutdown. Dispose alone does not publish
      # close_notify under Windows PowerShell 5.1, which correctly makes the
      # Jolt client classify an otherwise complete HTTP response as truncated.
      $null = $tls.ShutdownAsync().GetAwaiter().GetResult()
      $outcomes.Add("served")
    }
    catch {
      # The first and third clients deliberately retain automatic certificate
      # validation and reject this self-signed fixture. The Jolt test asserts
      # the client-side SSLException; this process records the peer abort.
      $outcomes.Add("failed")
    }
    finally {
      if ($tls) {
        try { $tls.Dispose() } catch {}
      }
      try { $client.Dispose() } catch {}
    }
  }

  $observed = [string]::Join(",", $outcomes)
  if ($observed -ne "failed,served,failed") {
    throw "unexpected Schannel fixture outcomes: $observed"
  }
  [Console]::Out.WriteLine("fixture outcomes: $observed")
  [Console]::Out.Flush()
}
catch {
  [Console]::Error.WriteLine($_.Exception.ToString())
  exit 1
}
finally {
  if ($listener) {
    try { $listener.Stop() } catch {}
  }
  if ($certificate) {
    try { $certificate.Dispose() } catch {}
  }
}

exit 0
