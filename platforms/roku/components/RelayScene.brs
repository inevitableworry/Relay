sub init()
  m.top.setFocus(true)
  m.sections = ["Connect to your PS5", "Cloud Streaming", "Search", "Settings"]
  m.selected = 0
end sub
function onKeyEvent(key as string, press as boolean) as boolean
  if not press then return false
  if key = "right" then m.selected = (m.selected + 1) mod m.sections.count()
  if key = "left" then m.selected = (m.selected + m.sections.count() - 1) mod m.sections.count()
  if key = "right" or key = "left" then
    m.top.findNode("title").text = m.sections[m.selected]
    return true
  end if
  if key = "OK" then
    m.top.findNode("status").text = "UI only: Roku streaming backend is not implemented"
    return true
  end if
  return false
end function
