sub Main()
  screen = CreateObject("roSGScreen")
  port = CreateObject("roMessagePort")
  screen.SetMessagePort(port)
  screen.CreateScene("RelayScene")
  screen.Show()
  while true
    message = wait(0,port)
    if type(message) = "roSGScreenEvent" and message.isScreenClosed() then exit while
  end while
end sub
